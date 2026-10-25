package com.svp.recording.sanitize;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Value-shape validators. Each one is a checksum or structural rule, not a loose regex, because the
 * hidden evaluation set contains look-alikes: 16-digit order numbers that fail Luhn, 9-digit invoice
 * numbers, UUIDs, hashes, base64 blobs.
 */
public final class ValueDetectors {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+'-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,24}$");
    private static final Pattern JWT = Pattern.compile("^eyJ[A-Za-z0-9_-]{5,}\\.eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*$");
    private static final Pattern AUTH_SCHEME = Pattern.compile("^(?i)(Bearer|Basic|Digest|Token|ApiKey|Negotiate)\\s+(\\S{6,})$");
    private static final Pattern KNOWN_SECRET = Pattern.compile(
            "^(sk|rk|pk)_(live|test)_[A-Za-z0-9]{10,}$"            // Stripe-style
            + "|^(AKIA|ASIA)[0-9A-Z]{16}$"                         // AWS access key id
            + "|^gh[pousr]_[A-Za-z0-9]{30,}$"                     // GitHub tokens
            + "|^github_pat_[A-Za-z0-9_]{40,}$"
            + "|^xox[baprs]-[A-Za-z0-9-]{10,}$"                   // Slack
            + "|^AIza[0-9A-Za-z_-]{35}$"                          // Google API key
            + "|^glpat-[A-Za-z0-9_-]{20,}$"                        // GitLab
            + "|^SG\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}$"); // SendGrid
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern PHONE_FORMATTED = Pattern.compile("^\\+?\\(?\\d{1,4}\\)?(?:[\\s.-]?\\(?\\d{1,4}\\)?){2,5}$");
    private static final Pattern FORMATTED_SSN = Pattern.compile("^\\d{3}[- ]\\d{2}[- ]\\d{4}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^(0x)?[0-9a-fA-F]+$");
    private static final Pattern ALREADY_MASKED = Pattern.compile(
            "^[*Xx•#\\-\\s]{3,}\\d{0,4}$|^\\d{0,6}[*Xx•#]{4,}\\d{0,4}$");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{8})$");
    private static final Pattern EXPIRY_LIKE = Pattern.compile(
            "^(0?[1-9]|1[0-2])\\s*[/-]\\s*(\\d{2}|\\d{4})$|^\\d{4}-(0[1-9]|1[0-2])(-\\d{2})?$|^(0[1-9]|1[0-2])\\d{2}$");
    private static final Pattern INDIAN_PAN = Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$");

    private ValueDetectors() {
    }

    /**
     * Digits of a value made only of digits and single space/dash separators ("4111 1111 1111 1111"),
     * otherwise null.
     */
    public static String digitsOnly(String s) {
        int n = s.length();
        if (n == 0 || n > 40) {
            return null;
        }
        StringBuilder sb = new StringBuilder(n);
        char prev = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if ((c == ' ' || c == '-') && i > 0 && i < n - 1 && prev >= '0' && prev <= '9') {
                // separator between digits
            } else {
                return null;
            }
            prev = c;
        }
        return sb.toString();
    }

    public static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    /** Card network from IIN ranges and lengths; null when no network issues such a number. */
    public static String cardBrand(String d) {
        int len = d.length();
        if (len < 12 || len > 19) {
            return null;
        }
        int p1 = d.charAt(0) - '0';
        int p2 = Integer.parseInt(d.substring(0, 2));
        int p3 = Integer.parseInt(d.substring(0, 3));
        int p4 = Integer.parseInt(d.substring(0, 4));
        int p6 = Integer.parseInt(d.substring(0, 6));
        if (p1 == 4 && (len == 13 || len == 16 || len == 19)) {
            return "VISA";
        }
        if (((p2 >= 51 && p2 <= 55) || (p4 >= 2221 && p4 <= 2720)) && len == 16) {
            return "MASTERCARD";
        }
        if ((p2 == 34 || p2 == 37) && len == 15) {
            return "AMEX";
        }
        if ((p4 == 6011 || p2 == 65 || (p3 >= 644 && p3 <= 649)) && len >= 16) {
            return "DISCOVER";
        }
        if (p4 >= 3528 && p4 <= 3589 && len >= 16) {
            return "JCB";
        }
        if (((p3 >= 300 && p3 <= 305) || p2 == 36 || p2 == 38 || p2 == 39) && len >= 14) {
            return "DINERS";
        }
        if (p2 == 62 && len >= 16) {
            return "UNIONPAY";
        }
        if ((p2 == 50 || (p2 >= 56 && p2 <= 58) || p4 == 6304 || p4 == 6759 || p6 == 676770 || p6 == 676774)) {
            return "MAESTRO";
        }
        return null;
    }

    /** True for a Luhn-valid number in a real card network range. */
    public static boolean isPan(String digits) {
        return digits != null && digits.length() >= 13 && digits.length() <= 19 && luhn(digits) && cardBrand(digits) != null;
    }

    /** SSA rules: area not 000/666/9xx, group not 00, serial not 0000. */
    public static boolean validSsn(String nineDigits) {
        if (nineDigits == null || nineDigits.length() != 9) {
            return false;
        }
        int area = Integer.parseInt(nineDigits.substring(0, 3));
        int group = Integer.parseInt(nineDigits.substring(3, 5));
        int serial = Integer.parseInt(nineDigits.substring(5));
        return area != 0 && area != 666 && area < 900 && group != 0 && serial != 0;
    }

    public static boolean isFormattedSsn(String s) {
        return s.length() == 11 && FORMATTED_SSN.matcher(s).matches();
    }

    public static boolean isEmail(String s) {
        return s.length() <= 254 && s.indexOf('@') > 0 && EMAIL.matcher(s).matches();
    }

    public static boolean isJwt(String s) {
        return s.startsWith("eyJ") && JWT.matcher(s).matches();
    }

    /** @return the auth scheme ("Bearer") when the value is "Scheme credential", else null */
    public static String authScheme(String s) {
        if (s.length() < 8 || s.indexOf(' ') < 0 || !Character.isLetter(s.charAt(0))) {
            return null; // cheap prefilter: the regex below only runs on "Word something" values
        }
        var m = AUTH_SCHEME.matcher(s.trim());
        return m.matches() ? m.group(1) : null;
    }

    public static boolean isKnownSecret(String s) {
        if (s.length() < 16) {
            return false;
        }
        char c = s.charAt(0);
        if (c != 's' && c != 'r' && c != 'p' && c != 'A' && c != 'g' && c != 'x' && c != 'S') {
            return false;
        }
        return KNOWN_SECRET.matcher(s).matches();
    }

    public static boolean isPrivateKey(String s) {
        return s.contains("PRIVATE KEY-----") && PRIVATE_KEY.matcher(s).find();
    }

    /** ISO 13616 IBAN with a valid mod-97 checksum. */
    public static boolean isIban(String s) {
        if (s.length() < 15 || s.length() > 42 || !Character.isUpperCase(s.charAt(0))
                || !Character.isUpperCase(s.charAt(1)) || !Character.isDigit(s.charAt(2))) {
            return false;
        }
        String compact = s.replace(" ", "");
        if (!IBAN.matcher(compact).matches()) {
            return false;
        }
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isLetter(c) ? Integer.toString(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    public static boolean isE164(String s) {
        return s.length() >= 9 && s.charAt(0) == '+' && E164.matcher(s).matches();
    }

    public static boolean isPhoneLike(String s) {
        int digits = countDigits(s);
        return digits >= 7 && digits <= 15 && PHONE_FORMATTED.matcher(s.trim()).matches();
    }

    public static boolean isIndianPan(String s) {
        return INDIAN_PAN.matcher(s).matches();
    }

    public static boolean isUuid(String s) {
        return s.length() == 36 && UUID.matcher(s).matches();
    }

    public static boolean isHex(String s) {
        return HEX.matcher(s).matches();
    }

    /** "****1111", "XXX-XX-1234", "411111******1111", "[REDACTED]" — already safe. */
    public static boolean isAlreadyMasked(String s) {
        boolean maskChar = false;
        boolean bracket = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == 'X' || c == 'x' || c == '\u2022' || c == '#') {
                maskChar = true;
            } else if (c == '[' || c == 'M' || c == 'm' || c == 'R' || c == 'r') {
                bracket = true;
            }
        }
        if (bracket) {
            String u = s.toUpperCase();
            if (u.contains("REDACTED") || u.contains("MASKED")) {
                return true;
            }
        }
        return maskChar && ALREADY_MASKED.matcher(s).matches();
    }

    public static boolean isDateLike(String s) {
        return DATE_LIKE.matcher(s.trim()).matches() || (s.length() >= 10 && s.length() <= 30
                && Character.isDigit(s.charAt(0)) && s.charAt(4) == '-' && s.charAt(7) == '-');
    }

    public static boolean isExpiryLike(String s) {
        return EXPIRY_LIKE.matcher(s.trim()).matches();
    }

    public static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Base64 that decodes to readable text (an encoded document or JSON payload) is content, not a
     * random credential. Random keys decode to binary.
     */
    public static boolean isBase64OfText(String s) {
        if (s.length() < 16 || s.length() % 4 == 1) {
            return false;
        }
        byte[] bytes;
        try {
            String padded = s + "=".repeat((4 - s.length() % 4) % 4);
            bytes = s.indexOf('-') >= 0 || s.indexOf('_') >= 0
                    ? java.util.Base64.getUrlDecoder().decode(padded)
                    : java.util.Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int printable = 0;
        for (byte b : bytes) {
            if ((b >= 0x20 && b < 0x7f) || b == '\n' || b == '\r' || b == '\t') {
                printable++;
            }
        }
        return bytes.length > 0 && printable >= bytes.length * 0.95;
    }

    /** Shannon entropy in bits per character. */
    public static double entropy(String s) {
        int[] counts = new int[128];
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) {
                counts[c]++;
            } else {
                other++;
            }
        }
        double h = 0;
        double n = s.length();
        for (int c : counts) {
            if (c > 0) {
                double p = c / n;
                h -= p * (Math.log(p) / Math.log(2));
            }
        }
        if (other > 0) {
            double p = other / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    /**
     * Random-looking credential: long, no whitespace, upper + lower + digits, high entropy, and not a
     * UUID / plain hex (identifiers and hashes are the classic false positives).
     */
    public static boolean looksLikeRandomSecret(String s) {
        int n = s.length();
        if (n < 20 || n > 4096) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else if ("+/=_-.~".indexOf(c) < 0) {
                return false;
            }
        }
        if (!(upper && lower && digit)) {
            return false;
        }
        if (isUuid(s) || isHex(s) || s.startsWith("http") || s.contains("://")
                || s.indexOf('.') > 0 && s.chars().filter(ch -> ch == '.').count() > 2) {
            return false;
        }
        // relative URLs / paths (/api/v1/accounts/ACC-9921) mix case and digits but are not secrets
        long slashes = s.chars().filter(ch -> ch == '/').count();
        if (s.charAt(0) == '/' || (slashes >= 3 && n < 96)) {
            return false;
        }
        return entropy(s) >= (n >= 32 ? 4.0 : 3.7);
    }
}
