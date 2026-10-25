package com.svp.recording.sanitize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a field by its <em>name</em> (and its parent's name). Names are tokenised
 * ({@code customerEmailAddress} → [customer, email, address], {@code X-API-Key} → [x, api, key])
 * so rules match on whole words: {@code company} never trips the {@code pan} rule.
 *
 * <p>Name evidence alone is never enough to mask a value that looks harmless — the sanitizer
 * combines it with value-shape validators to produce a confidence score.
 */
public final class KeyClassifier {

    /** Name-based hint. {@code identifierLike} marks names like request_id / checksum / sku. */
    public record KeyHint(SensitiveType type, boolean identifierLike) {
        static final KeyHint NONE = new KeyHint(null, false);
    }

    private static final Set<String> CARD_NEGATORS = Set.of("type", "brand", "network", "scheme", "last4", "lastfour",
            "bin", "iin", "country", "issuer", "fingerprint", "token", "tokenized", "masked", "mask", "hash", "id",
            "status", "category", "level", "product", "funding", "present", "count", "expiry", "expiration", "exp",
            "holder", "name", "art", "image", "color", "nickname", "alias", "ref", "reference");
    private static final Set<String> CARD_NUMBER_JOINED = Set.of("cardnumber", "creditcard", "debitcard", "ccnumber",
            "ccnum", "cardno", "cardnum", "primaryaccountnumber", "creditcardnumber", "debitcardnumber", "cardnbr");
    private static final Set<String> NUMBER_WORDS = Set.of("number", "num", "no", "nbr", "pan");
    private static final Set<String> CVV_TOKENS = Set.of("cvv", "cvc", "cvv2", "cvc2", "csc", "cvn", "cav2", "cvd");
    private static final Set<String> PASSWORD_TOKENS = Set.of("password", "passwd", "pwd", "passphrase", "passcode");
    private static final Set<String> PASSWORD_NEGATORS = Set.of("policy", "length", "expires", "expiry", "changed",
            "updated", "strength", "rules", "required", "min", "max", "last", "age", "reset", "set", "hint", "complexity");
    private static final Set<String> PERSON_PARENTS = Set.of("card", "cardholder", "holder", "customer", "payer", "payee",
            "beneficiary", "billing", "shipping", "contact", "person", "applicant", "owner", "sender", "recipient",
            "patient", "employee", "guardian", "nominee");
    private static final Set<String> NATIONAL_ID_JOINED = Set.of("passport", "passportnumber", "passportno",
            "driverslicense", "driverlicense", "drivinglicence", "drivinglicense", "driverslicensenumber",
            "nationalid", "nationalidnumber", "aadhaar", "aadhar", "aadhaarnumber", "taxid", "taxpayerid",
            "taxidentificationnumber", "itin", "nino", "nationalinsurancenumber", "pannumber", "pancard", "panno",
            "voterid", "socialinsurancenumber");
    private static final Set<String> NATIONAL_ID_TOKENS = Set.of("sin", "tin", "nid", "aadhaar", "aadhar", "passport");
    private static final Set<String> PHONE_TOKENS = Set.of("phone", "mobile", "msisdn", "telephone", "tel", "cell",
            "cellphone", "fax", "whatsapp", "phonenumber", "mobilenumber");
    private static final Set<String> PHONE_NEGATORS = Set.of("type", "verified", "country", "code", "prefix", "ext",
            "extension", "carrier", "count", "enabled", "consent", "opt", "optin");
    private static final Set<String> PERSON_NAME_JOINED = Set.of("firstname", "lastname", "fullname", "givenname",
            "familyname", "middlename", "surname", "cardholdername", "cardholder", "nameoncard", "holdername",
            "accountholder", "accountholdername", "customername", "payeename", "payername", "beneficiaryname",
            "sendername", "recipientname", "contactname", "legalname", "maidenname", "mothersmaidenname",
            "billingname", "shippingname", "employeename", "patientname", "personname", "forename");
    private static final Set<String> NAME_QUALIFIERS = Set.of("first", "last", "full", "given", "family", "middle",
            "customer", "cardholder", "holder", "payee", "payer", "beneficiary", "sender", "recipient", "contact",
            "legal", "person", "billing", "shipping", "employee", "patient");
    private static final Set<String> ADDRESS_JOINED = Set.of("address", "streetaddress", "homeaddress", "billingaddress",
            "shippingaddress", "mailingaddress", "residentialaddress", "postaladdress", "addressline1", "addressline2",
            "addressline", "address1", "address2", "street", "streetline1", "streetname", "houseaddress");
    private static final Set<String> ADDRESS_LINE_TOKENS = Set.of("line1", "line2", "line3", "street", "address1",
            "address2", "streetaddress", "houseno", "housenumber", "building", "flat", "apartment");
    private static final Set<String> ADDRESS_NEGATORS = Set.of("email", "ip", "mac", "wallet", "url", "web", "server",
            "host", "remote", "ipv4", "ipv6", "bitcoin", "crypto", "contract", "type", "id", "verified", "count",
            "city", "country", "state", "zip", "postcode", "postal", "code", "region");
    private static final Set<String> BANK_JOINED = Set.of("accountnumber", "acctnumber", "acctno", "accountno",
            "bankaccount", "bankaccountnumber", "iban", "bban", "accountnum", "bankacctnumber", "beneficiaryaccount");
    private static final Set<String> SECRET_TOKENS = Set.of("secret", "token", "apikey", "accesskey", "secretkey",
            "clientsecret", "otp", "totp", "credential", "credentials", "authorization", "cookie", "bearer", "jwt",
            "xsrf", "csrf", "sessiontoken", "refreshtoken", "accesstoken", "idtoken", "signingkey", "encryptionkey",
            "privatekey", "apisecret", "authtoken", "securityanswer", "mfacode", "verificationcode");
    private static final Set<String> KEY_QUALIFIERS = Set.of("api", "access", "secret", "private", "signing",
            "encryption", "client", "master", "auth", "license", "subscription", "webhook");
    private static final Set<String> SECRET_NEGATORS = Set.of("type", "expires", "expiry", "expiration", "ttl", "scope",
            "scopes", "count", "length", "url", "uri", "endpoint", "hint", "policy", "required", "enabled", "issuer",
            "format", "id", "name", "version", "lifetime", "in", "at", "status", "valid", "used", "masked", "last4",
            "prefix", "rotated", "created", "updated", "label", "description");
    private static final Set<String> IDENTIFIER_TOKENS = Set.of("id", "uuid", "guid", "hash", "checksum", "digest",
            "etag", "signature", "nonce", "trace", "traceid", "correlation", "request", "reference", "ref", "sku",
            "version", "commit", "sha", "sha1", "sha256", "md5", "idempotency", "txn", "transaction", "order",
            "invoice", "url", "uri", "path", "href", "link", "image", "file", "filename", "mime", "slug", "fingerprint",
            "code", "number", "no", "receipt", "confirmation", "booking", "tracking", "batch", "job", "event",
            "message", "msg", "span", "parent", "session", "device", "merchant", "account", "customer", "user",
            "payment", "product", "item", "plan", "promo", "coupon", "campaign", "template", "key");

    private KeyClassifier() {
    }

    /** Splits snake_case, kebab-case, camelCase, PascalCase and ACRONYMWords into lower-case tokens. */
    public static List<String> tokens(String key) {
        if (key == null || key.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(4);
        StringBuilder cur = new StringBuilder();
        int n = key.length();
        for (int i = 0; i < n; i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(cur, out);
                continue;
            }
            if (cur.length() > 0 && Character.isUpperCase(c)) {
                char prev = key.charAt(i - 1);
                boolean nextLower = i + 1 < n && Character.isLowerCase(key.charAt(i + 1));
                if (Character.isLowerCase(prev) || (Character.isUpperCase(prev) && nextLower)) {
                    flush(cur, out);
                }
            }
            cur.append(Character.toLowerCase(c));
        }
        flush(cur, out);
        return Collections.unmodifiableList(out);
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static final int CACHE_LIMIT = 20_000;
    private static final java.util.concurrent.ConcurrentHashMap<String, KeyHint> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Field names repeat constantly in real traffic, so classification is memoised (bounded: once
     * full, new names are classified without caching).
     */
    public static KeyHint classify(String key, String parentKey) {
        if (key == null) {
            return KeyHint.NONE;
        }
        String cacheKey = parentKey == null ? key : key + '\u0000' + parentKey;
        KeyHint cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        KeyHint hint = classifyUncached(key, parentKey);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(cacheKey, hint);
        }
        return hint;
    }

    static KeyHint classifyUncached(String key, String parentKey) {
        List<String> t = tokens(key);
        if (t.isEmpty()) {
            return KeyHint.NONE;
        }
        String joined = String.join("", t);
        List<String> parent = tokens(parentKey);
        boolean identifierLike = containsAny(t, IDENTIFIER_TOKENS);

        if (containsAny(t, PASSWORD_TOKENS) && !containsAny(t, PASSWORD_NEGATORS)) {
            return hint(SensitiveType.PASSWORD);
        }
        if (t.contains("private") && t.contains("key") || joined.contains("privatekey")) {
            return hint(SensitiveType.PRIVATE_KEY);
        }
        if (joined.contains("track1") || joined.contains("track2") || joined.contains("trackdata")
                || joined.contains("magstripe")) {
            return hint(SensitiveType.TRACK_DATA);
        }
        if (containsAny(t, CVV_TOKENS) || joined.contains("securitycode") || joined.contains("cardverification")
                || (t.contains("cid") && (t.contains("card") || parent.contains("card")))) {
            return hint(SensitiveType.CVV);
        }
        if (t.contains("pin") || joined.contains("pinblock")) {
            return hint(SensitiveType.PIN);
        }
        if (NATIONAL_ID_JOINED.contains(joined) || containsAny(t, NATIONAL_ID_TOKENS)) {
            return hint(SensitiveType.NATIONAL_ID);
        }
        boolean cardNegated = containsAny(t, CARD_NEGATORS) || joined.contains("lastfour") || joined.contains("last4")
                || (t.contains("last") && (t.contains("four") || t.contains("4") || t.contains("digits")));
        if (!cardNegated && (containsSubstring(joined, CARD_NUMBER_JOINED) || t.contains("pan")
                || t.equals(List.of("cc"))
                || (t.contains("card") && containsAny(t, NUMBER_WORDS))
                || ((parent.contains("card") || parent.contains("payment")) && containsAny(t, NUMBER_WORDS)
                    && t.size() == 1))) {
            return hint(SensitiveType.PAN);
        }
        if (t.contains("expiry") || t.contains("expiration") || joined.contains("expdate")
                || joined.contains("cardexp") || joined.equals("expirationdate")
                || (t.contains("exp") && (t.contains("month") || t.contains("year") || t.contains("date")
                    || t.contains("mm") || t.contains("yy") || t.contains("yyyy")))
                || (t.equals(List.of("exp")) && parent.contains("card"))) {
            return hint(SensitiveType.CARD_EXPIRY);
        }
        boolean lastFour = joined.contains("last4") || joined.contains("lastfour");
        if ((t.contains("ssn") || joined.contains("socialsecurity")) && !lastFour) {
            return hint(SensitiveType.SSN);
        }
        if (t.contains("email") || joined.equals("emailaddress") || joined.equals("mail") || t.contains("emails")) {
            return hint(SensitiveType.EMAIL);
        }
        if (containsAny(t, PHONE_TOKENS) && !containsAny(t, PHONE_NEGATORS)) {
            return hint(SensitiveType.PHONE);
        }
        if (t.contains("dob") || joined.contains("dateofbirth") || joined.contains("birthdate")
                || joined.contains("birthday") || (t.contains("birth") && t.contains("date"))) {
            return hint(SensitiveType.DATE_OF_BIRTH);
        }
        if (PERSON_NAME_JOINED.contains(joined)
                || ((t.contains("name") || t.contains("surname")) && containsAny(t, NAME_QUALIFIERS))
                || ((t.equals(List.of("name")) || t.equals(List.of("full", "name"))) && containsAny(parent, PERSON_PARENTS))) {
            return hint(SensitiveType.PERSON_NAME);
        }
        if (!containsAny(t, ADDRESS_NEGATORS)
                && (ADDRESS_JOINED.contains(joined)
                    || (parent.contains("address") && containsAny(t, ADDRESS_LINE_TOKENS)))) {
            return hint(SensitiveType.ADDRESS);
        }
        if (BANK_JOINED.contains(joined)) {
            return hint(SensitiveType.BANK_ACCOUNT);
        }
        if (!containsAny(t, SECRET_NEGATORS)) {
            if (containsAny(t, SECRET_TOKENS) || SECRET_TOKENS.contains(joined)) {
                return hint(t.contains("key") || joined.endsWith("key") ? SensitiveType.API_KEY : SensitiveType.ACCESS_TOKEN);
            }
            if (t.contains("key") && containsAny(t, KEY_QUALIFIERS)) {
                return hint(SensitiveType.API_KEY);
            }
            if (t.contains("auth") && (t.contains("token") || t.contains("key") || t.contains("header"))) {
                return hint(SensitiveType.ACCESS_TOKEN);
            }
        }
        return new KeyHint(null, identifierLike);
    }

    private static KeyHint hint(SensitiveType type) {
        return new KeyHint(type, false);
    }

    private static boolean containsAny(List<String> tokens, Set<String> set) {
        for (String t : tokens) {
            if (set.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSubstring(String joined, Set<String> fragments) {
        for (String f : fragments) {
            if (joined.contains(f)) {
                return true;
            }
        }
        return false;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
