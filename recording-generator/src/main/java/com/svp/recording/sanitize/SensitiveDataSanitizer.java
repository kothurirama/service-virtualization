package com.svp.recording.sanitize;

import com.svp.recording.RawMessage;
import com.svp.recording.RawTrafficLog;

import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PCI-DSS / PII / credential sanitiser for recorded traffic.
 *
 * <p>Every scalar in the path, query, headers and bodies is classified with two independent kinds
 * of evidence and a confidence score:
 * <ol>
 *   <li><b>Value validators</b> (checksums and structure): Luhn + card-network IIN ranges, SSA SSN
 *       rules, IBAN mod-97, JWT structure, known key prefixes, E.164 — strong on their own.</li>
 *   <li><b>Field names</b> (tokenised, with negators such as {@code card_last_four},
 *       {@code token_type}) — strong when the value shape agrees, weak when it does not.</li>
 * </ol>
 * Unknown high-entropy strings are masked <em>and</em> sent to review (fail closed). Free text is
 * scanned for embedded cards, SSNs, emails and tokens. Card expiry is masked only when the same
 * exchange carries a PAN (PCI scope rule).
 *
 * <p>Single pass over each tree, precompiled patterns, no reflection: thousands of logs per second.
 */
public final class SensitiveDataSanitizer {

    /** At or above this confidence a detection is masked silently; below it is masked and reviewed. */
    public static final double AUTO_THRESHOLD = 0.90;

    private record Finding(SensitiveType type, double confidence, String detector, String reason, boolean deferred) {
    }

    /** Expiry value waiting to learn whether the exchange contains a PAN. */
    private static final class Deferred {
        final Object original;
        final String field;
        final String key;
        final Finding finding;
        Consumer<Object> setter;

        Deferred(Object original, String field, String key, Finding finding) {
            this.original = original;
            this.field = field;
            this.key = key;
            this.finding = finding;
        }
    }

    private static final class Ctx {
        final List<Detection> detections = new ArrayList<>();
        final List<Deferred> deferred = new ArrayList<>();
        boolean panFound;
        int inspected;
    }

    private static final Set<String> PLACEHOLDERS = Set.of("", "null", "none", "n/a", "na", "-", "***", "true", "false",
            "bearer", "basic", "undefined", "nil", "empty");

    private static final Pattern TEXT_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern TEXT_JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern TEXT_AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern TEXT_SECRET = Pattern.compile(
            "\\b((sk|rk|pk)_(live|test)_[A-Za-z0-9]{10,}|(AKIA|ASIA)[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}"
                    + "|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}|glpat-[A-Za-z0-9_-]{20,})\\b");
    private static final Pattern TEXT_EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+'-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,24}");
    private static final Pattern TEXT_PAN = Pattern.compile("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");
    private static final Pattern TEXT_SSN = Pattern.compile("(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])");

    private final Masker masker;

    public SensitiveDataSanitizer() {
        this(new Masker());
    }

    public SensitiveDataSanitizer(Masker masker) {
        this.masker = masker;
    }

    /** Result of sanitising an arbitrary JSON tree (used by the inspection API). */
    public record Inspection(Object sanitized, List<Detection> detections, int fieldsInspected) {
    }

    public Inspection inspect(Object tree) {
        Ctx ctx = new Ctx();
        Object out = walk(tree, null, null, "body", ctx);
        settleDeferred(ctx);
        return new Inspection(settle(out), List.copyOf(ctx.detections), ctx.inspected);
    }

    public SanitizedLog sanitize(RawTrafficLog log) {
        Ctx ctx = new Ctx();
        String rawPath = log.path() == null ? "/" : log.path();
        int q = rawPath.indexOf('?');
        String pathOnly = q < 0 ? rawPath : rawPath.substring(0, q);
        Map<String, String> query = q < 0 ? Map.of() : parseQuery(rawPath.substring(q + 1));

        String path = sanitizePath(pathOnly, ctx);
        Map<String, String> safeQuery = sanitizeStrings(query, "request.query", ctx);

        RawMessage req = log.request() == null ? new RawMessage(null, Map.of(), null) : log.request();
        RawMessage res = log.response() == null ? new RawMessage(200, Map.of(), null) : log.response();
        Map<String, String> reqHeaders = sanitizeStrings(req.headers(), "request.headers", ctx);
        Object reqBody = settle(walk(req.body(), null, null, "request.body", ctx));
        Map<String, String> resHeaders = sanitizeStrings(res.headers(), "response.headers", ctx);
        Object resBody = settle(walk(res.body(), null, null, "response.body", ctx));
        settleDeferred(ctx); // card expiry is only in PCI scope when this exchange carried a PAN
        return new SanitizedLog(log.protocol() == null ? "HTTP" : log.protocol().toUpperCase(Locale.ROOT),
                log.method() == null ? "GET" : log.method().toUpperCase(Locale.ROOT),
                path, safeQuery, reqHeaders, reqBody, res.statusCode() == null ? 200 : res.statusCode(), resHeaders,
                resBody, List.copyOf(ctx.detections), ctx.inspected);
    }

    private static Object settle(Object v) {
        return v instanceof Deferred d ? d.original : v;
    }

    private void settleDeferred(Ctx ctx) {
        if (!ctx.panFound) {
            return;
        }
        for (Deferred d : ctx.deferred) {
            Object masked = masker.mask(d.finding.type(), d.original, d.key);
            if (d.setter != null) {
                d.setter.accept(masked);
            }
            ctx.detections.add(new Detection(d.field, d.finding.type(), d.finding.type().category(),
                    d.finding.confidence(), d.finding.detector(), d.finding.reason(), preview(masked)));
        }
        ctx.deferred.clear();
    }

    // ---------------------------------------------------------------- tree walk

    private Object walk(Object value, String key, String parentKey, String field, Ctx ctx) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size() * 2);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = walk(e.getValue(), k, key, field + "." + k, ctx);
                if (v instanceof Deferred d) {
                    out.put(k, d.original);
                    d.setter = masked -> out.put(k, masked);
                    ctx.deferred.add(d);
                } else {
                    out.put(k, v);
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object v = walk(list.get(i), key, parentKey, field + "[" + i + "]", ctx);
                if (v instanceof Deferred d) {
                    int idx = i;
                    out.add(d.original);
                    d.setter = masked -> out.set(idx, masked);
                    ctx.deferred.add(d);
                } else {
                    out.add(v);
                }
            }
            return out;
        }
        return leaf(value, key, parentKey, field, ctx);
    }

    private Map<String, String> sanitizeStrings(Map<String, String> in, String prefix, Ctx ctx) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            Object v = leaf(e.getValue(), e.getKey(), null, prefix + "." + e.getKey(), ctx);
            if (v instanceof Deferred d) {
                out.put(e.getKey(), String.valueOf(d.original));
                String k = e.getKey();
                d.setter = masked -> out.put(k, String.valueOf(masked));
                ctx.deferred.add(d);
            } else {
                out.put(e.getKey(), v == null ? null : String.valueOf(v));
            }
        }
        return out;
    }

    private String sanitizePath(String path, Ctx ctx) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        String previous = null;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (i > 0) {
                out.append('/');
            }
            if (seg.isEmpty()) {
                continue;
            }
            String decoded = URLDecoder.decode(seg, StandardCharsets.UTF_8);
            Object v = leafNoCount(decoded, previous, null, "request.path", ctx);
            String safe = v instanceof Deferred d ? String.valueOf(d.original) : String.valueOf(v);
            out.append(safe.equals(decoded) ? seg : URLEncoder.encode(safe, StandardCharsets.UTF_8));
            previous = seg;
        }
        ctx.inspected++; // the path counts as one field
        return out.toString();
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    // ---------------------------------------------------------------- leaf classification

    private Object leaf(Object value, String key, String parentKey, String field, Ctx ctx) {
        ctx.inspected++;
        return leafNoCount(value, key, parentKey, field, ctx);
    }

    private Object leafNoCount(Object value, String key, String parentKey, String field, Ctx ctx) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        String s;
        if (value instanceof String str) {
            s = str;
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof BigInteger) {
            s = value.toString();
        } else if (value instanceof Number) {
            return value; // decimals (amounts, rates) are never identifiers
        } else {
            s = String.valueOf(value);
        }
        if (s.isBlank() || ValueDetectors.isAlreadyMasked(s)) {
            return value;
        }
        KeyClassifier.KeyHint hint = KeyClassifier.classify(key, parentKey);
        Finding f = detect(s, value, hint);
        if (f == null) {
            if (value instanceof String && s.length() >= 6) {
                return scanText(s, field, ctx);
            }
            return value;
        }
        if (f.deferred()) {
            return new Deferred(value, field, key, f);
        }
        Object masked = masker.mask(f.type(), value, key);
        if (f.type() == SensitiveType.PAN) {
            ctx.panFound = true;
        }
        ctx.detections.add(new Detection(field, f.type(), f.type().category(), f.confidence(), f.detector(), f.reason(),
                preview(masked)));
        return masked;
    }

    private static Finding found(SensitiveType type, double confidence, String detector, String reason) {
        return new Finding(type, confidence, detector, reason, false);
    }

    private static boolean isSecretHint(SensitiveType t) {
        return t != null && t.category() == Category.SECRET;
    }

    private Finding detect(String s, Object original, KeyClassifier.KeyHint hint) {
        SensitiveType h = hint.type();

        // 1. structures that are unambiguous on their own
        if (ValueDetectors.isPrivateKey(s)) {
            return found(SensitiveType.PRIVATE_KEY, 0.99, "pem_block", "PEM private key");
        }
        if (ValueDetectors.isJwt(s)) {
            return found(SensitiveType.JWT, 0.98, "jwt_structure", "JSON Web Token");
        }
        String scheme = ValueDetectors.authScheme(s);
        if (scheme != null) {
            String credential = s.trim().substring(scheme.length()).trim();
            if (credential.length() >= 12 || isSecretHint(h)) {
                return found(SensitiveType.AUTH_HEADER, 0.97, "auth_scheme", scheme + " credential");
            }
        }
        if (ValueDetectors.isKnownSecret(s)) {
            return found(SensitiveType.API_KEY, 0.98, "known_key_prefix", "credential with a well-known provider prefix");
        }

        // 2. numeric identifiers validated by checksum / rules, sharpened by the field name
        String digits = ValueDetectors.digitsOnly(s);
        if (digits != null) {
            int len = digits.length();
            if (ValueDetectors.isPan(digits)) {
                return found(SensitiveType.PAN, h == SensitiveType.PAN ? 0.99 : 0.97, "luhn_iin",
                        ValueDetectors.cardBrand(digits) + " card number (Luhn valid)");
            }
            if (h == SensitiveType.PAN && len >= 12 && len <= 19) {
                return found(SensitiveType.PAN, 0.75, "field_name",
                        "card-number field whose value fails Luhn/IIN checks (test or malformed PAN)");
            }
            if (ValueDetectors.isFormattedSsn(s)) {
                boolean valid = ValueDetectors.validSsn(digits);
                if (h == SensitiveType.SSN) {
                    return found(SensitiveType.SSN, valid ? 0.99 : 0.92, "ssn_format", "social security number");
                }
                if (valid && h != SensitiveType.PHONE) {
                    // fail closed: an SSN-shaped value is masked even in an id-like field, but a human confirms it
                    return hint.identifierLike()
                            ? found(SensitiveType.SSN, 0.85, "ssn_format_ssa_rules",
                                    "SSN-formatted value in an identifier-like field")
                            : found(SensitiveType.SSN, 0.93, "ssn_format_ssa_rules", "SSN-formatted value passing SSA rules");
                }
            }
            if (h == SensitiveType.SSN && len == 9) {
                return found(SensitiveType.SSN, 0.97, "field_name_9_digits", "SSN field");
            }
            if (h == SensitiveType.CVV && (len == 3 || len == 4)) {
                return found(SensitiveType.CVV, 0.97, "field_name_3_4_digits", "card verification value");
            }
            if (h == SensitiveType.PIN && len >= 4 && len <= 12) {
                return found(SensitiveType.PIN, 0.97, "field_name_digits", "PIN");
            }
            if (h == SensitiveType.NATIONAL_ID && len >= 6) {
                return found(SensitiveType.NATIONAL_ID, 0.95, "field_name_digits", "national identifier");
            }
            if (h == SensitiveType.BANK_ACCOUNT && len >= 6) {
                return found(SensitiveType.BANK_ACCOUNT, 0.93, "field_name_digits", "bank account number");
            }
            if (h == SensitiveType.PHONE && len >= 7 && len <= 15) {
                return found(SensitiveType.PHONE, 0.95, "field_name_digits", "phone number");
            }
            if (h == SensitiveType.DATE_OF_BIRTH && len == 8) {
                return found(SensitiveType.DATE_OF_BIRTH, 0.93, "field_name_date", "date of birth");
            }
            if (h == SensitiveType.CARD_EXPIRY && (len <= 4 || ValueDetectors.isExpiryLike(s))) {
                return new Finding(SensitiveType.CARD_EXPIRY, 0.92, "field_name_with_pan",
                        "card expiry stored alongside a PAN", true);
            }
            if (h == SensitiveType.PASSWORD || h == SensitiveType.ACCESS_TOKEN || h == SensitiveType.API_KEY) {
                return found(h, len >= 6 ? 0.95 : 0.9, "field_name", "credential field");
            }
        }

        // 3. other strongly-shaped values
        if (ValueDetectors.isEmail(s)) {
            return found(SensitiveType.EMAIL, 0.97, "email_format", "email address");
        }
        if (ValueDetectors.isIban(s)) {
            return found(SensitiveType.BANK_ACCOUNT, 0.95, "iban_mod97", "IBAN (checksum valid)");
        }
        if (ValueDetectors.isE164(s)) {
            return found(SensitiveType.PHONE, h == SensitiveType.PHONE ? 0.97 : 0.9, "e164", "E.164 phone number");
        }
        if (h == SensitiveType.PHONE && ValueDetectors.isPhoneLike(s)) {
            return found(SensitiveType.PHONE, 0.95, "field_name_phone_format", "phone number");
        }

        // 4. name-driven decisions for values without a checkable shape
        if (h != null) {
            String lower = s.trim().toLowerCase(Locale.ROOT);
            switch (h) {
                case PASSWORD -> {
                    if (!PLACEHOLDERS.contains(lower)) {
                        return found(SensitiveType.PASSWORD, 0.97, "field_name", "password field");
                    }
                }
                case PRIVATE_KEY -> {
                    return found(SensitiveType.PRIVATE_KEY, 0.95, "field_name", "private key field");
                }
                case API_KEY, ACCESS_TOKEN -> {
                    if (!PLACEHOLDERS.contains(lower)) {
                        return s.length() >= 8
                                ? found(h, 0.95, "field_name", "credential field")
                                : found(h, 0.7, "field_name", "credential-named field with an unusually short value");
                    }
                }
                case TRACK_DATA -> {
                    return found(SensitiveType.TRACK_DATA, 0.97, "field_name", "magnetic stripe track data");
                }
                case CVV -> {
                    return found(SensitiveType.CVV, 0.75, "field_name", "CVV field with an unexpected format");
                }
                case PIN -> {
                    return found(SensitiveType.PIN, 0.75, "field_name", "PIN field with an unexpected format");
                }
                case SSN -> {
                    if (ValueDetectors.countDigits(s) >= 4) {
                        return found(SensitiveType.SSN, 0.8, "field_name", "SSN field with an unexpected format");
                    }
                }
                case NATIONAL_ID -> {
                    return found(SensitiveType.NATIONAL_ID, ValueDetectors.isIndianPan(s) ? 0.97 : 0.93,
                            "field_name", "national identifier");
                }
                case PERSON_NAME -> {
                    if (s.length() <= 120 && s.chars().anyMatch(Character::isLetter)) {
                        return found(SensitiveType.PERSON_NAME, 0.92, "field_name", "person name");
                    }
                }
                case DATE_OF_BIRTH -> {
                    return found(SensitiveType.DATE_OF_BIRTH, ValueDetectors.isDateLike(s) ? 0.95 : 0.8,
                            "field_name", "date of birth");
                }
                case ADDRESS -> {
                    if (s.chars().anyMatch(Character::isLetter)) {
                        return found(SensitiveType.ADDRESS, 0.9, "field_name", "street address");
                    }
                }
                case BANK_ACCOUNT -> {
                    if (ValueDetectors.countDigits(s) >= 6) {
                        return found(SensitiveType.BANK_ACCOUNT, 0.9, "field_name", "bank account number");
                    }
                }
                case CARD_EXPIRY -> {
                    if (ValueDetectors.isExpiryLike(s)) {
                        return new Finding(SensitiveType.CARD_EXPIRY, 0.92, "field_name_with_pan",
                                "card expiry stored alongside a PAN", true);
                    }
                }
                default -> {
                    // EMAIL / PHONE / PAN named fields whose value failed validation are not masked
                }
            }
        }

        // 5. unknown random-looking strings: mask and route to a human (fail closed)
        if (original instanceof String && !hint.identifierLike() && ValueDetectors.looksLikeRandomSecret(s)
                && !ValueDetectors.isBase64OfText(s)) {
            return found(SensitiveType.HIGH_ENTROPY_SECRET, 0.65, "entropy",
                    "Unrecognized high-entropy pattern — flagged for security review");
        }
        return null;
    }

    /** Embedded secrets / identifiers inside free text (notes, URLs, error messages). */
    private Object scanText(String s, String field, Ctx ctx) {
        String text = s;
        SensitiveType first = null;
        Object[][] passes = {
                {TEXT_PRIVATE_KEY, SensitiveType.PRIVATE_KEY},
                {TEXT_JWT, SensitiveType.JWT},
                {TEXT_AUTH, SensitiveType.AUTH_HEADER},
                {TEXT_SECRET, SensitiveType.API_KEY},
                {TEXT_EMAIL, SensitiveType.EMAIL},
                {TEXT_PAN, SensitiveType.PAN},
                {TEXT_SSN, SensitiveType.SSN}};
        for (Object[] pass : passes) {
            Pattern p = (Pattern) pass[0];
            SensitiveType type = (SensitiveType) pass[1];
            Matcher m = p.matcher(text);
            StringBuilder sb = null;
            int last = 0;
            while (m.find()) {
                String match = m.group();
                if (type == SensitiveType.PAN && !ValueDetectors.isPan(match.replaceAll("[ -]", ""))) {
                    continue;
                }
                if (type == SensitiveType.SSN && !ValueDetectors.validSsn(match.replace("-", ""))) {
                    continue;
                }
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                }
                sb.append(text, last, m.start()).append(masker.maskInText(type, match));
                last = m.end();
                if (first == null) {
                    first = type;
                }
                if (type == SensitiveType.PAN) {
                    ctx.panFound = true;
                }
            }
            if (sb != null) {
                sb.append(text, last, text.length());
                text = sb.toString();
            }
        }
        if (first == null) {
            return s;
        }
        ctx.detections.add(new Detection(field, first, first.category(), 0.95, "free_text_scan",
                "sensitive value embedded in free text", preview(text)));
        return text;
    }

    private static String preview(Object masked) {
        String p = String.valueOf(masked);
        return p.length() > 60 ? p.substring(0, 57) + "..." : p;
    }
}
