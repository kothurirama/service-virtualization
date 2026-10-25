package com.svp.recording.sanitize;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces safe replacement values. Rules of thumb:
 * <ul>
 *   <li><b>Synthesise</b> where clients validate format (PAN → brand-matched network test card that
 *       passes Luhn; SSN → 000-00-0000, which the SSA never issues; email → pseudonymous address).</li>
 *   <li><b>Redact</b> credentials outright — a fake token is not more useful than {@code [REDACTED]}.</li>
 *   <li>Keep the JSON type (a numeric CVV stays a number) and the original layout (separators).</li>
 *   <li>Never derive the replacement from the original digits: no real BIN or last-four survives.</li>
 * </ul>
 * Pseudonyms are an HMAC with a per-process random key: stable within one ingest (so the same
 * customer email maps to the same pseudonym across stubs) but not reversible.
 */
public final class Masker {

    public static final String REDACTED = "[REDACTED]";

    /** Published network test numbers (cannot be charged). Second entry is used if the first equals the input. */
    private static final Map<String, List<String>> TEST_PANS = Map.of(
            "VISA", List.of("4242424242424242", "4000056655665556"),
            "MASTERCARD", List.of("5555555555554444", "5200828282828210"),
            "AMEX", List.of("378282246310005", "371449635398431"),
            "DISCOVER", List.of("6011111111111117", "6011000990139424"),
            "JCB", List.of("3566002020360505", "3530111333300000"),
            "DINERS", List.of("36227206271667", "30569309025904"),
            "UNIONPAY", List.of("6200000000000005", "6205500000000000004"),
            "MAESTRO", List.of("6759649826438453", "6799990100000000019"));

    private final byte[] key;

    public Masker() {
        this(randomKey());
    }

    public Masker(byte[] key) {
        this.key = key.clone();
    }

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    public Object mask(SensitiveType type, Object original, String fieldName) {
        String s = original instanceof String str ? str : String.valueOf(original);
        boolean numeric = original instanceof Number;
        return switch (type) {
            case PAN -> keepType(syntheticPan(s), numeric);
            case CVV, PIN -> numeric ? 0 : "0".repeat(Math.max(3, Math.min(s.length(), 12)));
            case CARD_EXPIRY -> syntheticExpiry(s, original);
            case SSN -> replaceDigits(s, '0');
            case BANK_ACCOUNT -> ValueDetectors.isIban(s) ? REDACTED : keepType(replaceDigits(s, '0'), numeric);
            case PHONE -> keepType(replaceDigits(s, '0'), numeric);
            case EMAIL -> "user_" + pseudonym(s.toLowerCase()) + "@masked.com";
            case PERSON_NAME -> syntheticName(fieldName);
            case DATE_OF_BIRTH -> syntheticDob(s, numeric);
            case ADDRESS -> "1 Test Street";
            case AUTH_HEADER -> {
                String scheme = ValueDetectors.authScheme(s);
                yield scheme == null ? REDACTED : scheme + " " + REDACTED;
            }
            case NATIONAL_ID, TRACK_DATA, PASSWORD, API_KEY, ACCESS_TOKEN, JWT, PRIVATE_KEY, HIGH_ENTROPY_SECRET ->
                    numeric ? 0 : REDACTED;
        };
    }

    /** Replacement for a sensitive substring inside free text. */
    public String maskInText(SensitiveType type, String match) {
        return switch (type) {
            case PAN -> syntheticPan(match);
            case SSN -> replaceDigits(match, '0');
            case EMAIL -> "user_" + pseudonym(match.toLowerCase()) + "@masked.com";
            case AUTH_HEADER -> {
                String scheme = ValueDetectors.authScheme(match);
                yield scheme == null ? REDACTED : scheme + " " + REDACTED;
            }
            default -> REDACTED;
        };
    }

    String syntheticPan(String original) {
        String digits = ValueDetectors.digitsOnly(original);
        String brand = digits == null ? null : ValueDetectors.cardBrand(digits);
        List<String> candidates = TEST_PANS.getOrDefault(brand == null ? "VISA" : brand, TEST_PANS.get("VISA"));
        String replacement = candidates.get(0).equals(digits) ? candidates.get(1) : candidates.get(0);
        if (digits != null && replacement.length() == digits.length()) {
            return layoutLike(original, replacement); // keep "4111 1111 ..." spacing
        }
        return replacement;
    }

    private static Object keepType(String masked, boolean numeric) {
        if (!numeric) {
            return masked;
        }
        String digits = masked.replaceAll("\\D", "");
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return masked;
        }
    }

    /** Puts {@code digits} into the digit positions of {@code layout}. */
    static String layoutLike(String layout, String digits) {
        StringBuilder sb = new StringBuilder(layout.length());
        int d = 0;
        for (int i = 0; i < layout.length(); i++) {
            char c = layout.charAt(i);
            sb.append(Character.isDigit(c) && d < digits.length() ? digits.charAt(d++) : c);
        }
        return sb.toString();
    }

    static String replaceDigits(String s, char with) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isDigit(c) ? with : c);
        }
        return sb.toString();
    }

    private static Object syntheticExpiry(String s, Object original) {
        if (original instanceof Number n) {
            long v = n.longValue();
            return v > 31 ? (v > 99 ? 2030 : 30) : 12; // year (4 or 2 digit) or month
        }
        String t = s.trim();
        if (t.matches("^\\d{1,2}\\s*/\\s*\\d{2}$")) {
            return "12/30";
        }
        if (t.matches("^\\d{1,2}\\s*/\\s*\\d{4}$")) {
            return "12/2030";
        }
        if (t.matches("^\\d{4}-\\d{2}$")) {
            return "2030-12";
        }
        if (t.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return "2030-12-31";
        }
        if (t.matches("^\\d{4}$")) {
            return "1230";
        }
        if (t.matches("^\\d{1,2}$")) {
            return "12";
        }
        return REDACTED;
    }

    private static Object syntheticDob(String s, boolean numeric) {
        if (numeric) {
            return 19700101;
        }
        String t = s.trim();
        if (t.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
            return "1970-01-01";
        }
        if (t.matches("^\\d{1,2}/\\d{1,2}/\\d{4}$")) {
            return "01/01/1970";
        }
        if (t.matches("^\\d{8}$")) {
            return "19700101";
        }
        return REDACTED;
    }

    private static String syntheticName(String fieldName) {
        List<String> t = KeyClassifier.tokens(fieldName);
        if (t.contains("first") || t.contains("given") || t.contains("forename") || String.join("", t).contains("firstname")) {
            return "Test";
        }
        if (t.contains("last") || t.contains("family") || t.contains("surname") || String.join("", t).contains("lastname")) {
            return "User";
        }
        return "Test User";
    }

    String pseudonym(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 3);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
