package com.svp.recording;

import com.svp.recording.sanitize.Detection;
import com.svp.recording.sanitize.KeyClassifier;
import com.svp.recording.sanitize.SensitiveDataSanitizer;
import com.svp.recording.sanitize.SensitiveType;
import com.svp.recording.sanitize.ValueDetectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerTest {

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer();

    private SensitiveType typeOf(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        List<Detection> d = sanitizer.inspect(body).detections();
        return d.isEmpty() ? null : d.get(0).type();
    }

    @ParameterizedTest(name = "{0}={1} -> {2}")
    @CsvSource({
            // value validators work whatever the field is called
            "note_ref, 4111111111111111, PAN",
            "misc, 4012-8888-8888-1881, PAN",
            "x, 378282246310005, PAN",
            "x, 6011111111111117, PAN",
            "x, 2223003122003222, PAN",
            "x, jane.doe@example.com, EMAIL",
            "x, GB82WEST12345698765432, BANK_ACCOUNT",
            "x, +14155550132, PHONE",
            "x, AKIAIOSFODNN7EXAMPLE, API_KEY",
            "x, sk_live_FAKEdemoKey0000, API_KEY",
            "x, 536-22-1987, SSN",
            // field names sharpen weak shapes
            "ssn, 536221987, SSN",
            "cvv, 737, CVV",
            "card_pin, 4321, PIN",
            "password, hunter2!, PASSWORD",
            "first_name, Asha, PERSON_NAME",
            "date_of_birth, 1990-02-01, DATE_OF_BIRTH",
            "street_address, 1 Infinite Loop, ADDRESS",
            "passport_number, N1234567, NATIONAL_ID",
            "pan_number, ABCDE1234F, NATIONAL_ID",
            "client_secret, xYz9aBcD3eFgH7iJkL1mNoP5qRsT, ACCESS_TOKEN",
            "Authorization, Bearer abcdefghijkl12345, AUTH_HEADER",
            "card_number, 4111111111111112, PAN"
    })
    void detects(String key, String value, SensitiveType expected) {
        assertThat(typeOf(key, value)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}={1} is not sensitive")
    @CsvSource({
            "order_number, 4111111111111112",      // Luhn fails, neutral name
            "statement_number, 1234567812345670",  // no card network
            "invoice_no, 123456789",
            "reference, 000-12-3456",              // invalid SSN area
            "card_last_four, 1111",
            "ssn_last4, 6789",
            "card_type, VISA",
            "token_type, Bearer",
            "auth_code, A1B2C3",
            "expires_in, 3600",
            "request_id, 3f2b8c1e-9a4d-4e6f-b1c2-7d8e9f0a1b2c",
            "checksum, 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "package, lodash@4.17.21",
            "mention, @ops-team",
            "merchant_name, Jane's Bakery",
            "pincode, 560001",
            "routing_number, 021000021",
            "phone_country_code, +91",
            "password_policy, min 12 chars",
            "masked_pan, 411111******1111",
            "attachment, SGVsbG8gV29ybGQhIFRoaXMgaXMgYSB0ZXN0IGF0dGFjaG1lbnQu",
            "plan, Basic premium"
    })
    void leavesLookAlikesAlone(String key, String value) {
        assertThat(typeOf(key, value)).isNull();
    }

    @Test
    void unformattedSsnShapeUnderANeutralKeyIsMaskedAndHeldForReview() {
        var r = sanitizer.inspect(Map.of("value", "536904399", "invoice_no", "123456789", "units", 536904399));
        assertThat(r.detections()).singleElement().satisfies(d -> {
            assertThat(d.field()).endsWith("value");
            assertThat(d.type()).isEqualTo(SensitiveType.SSN);
            assertThat(d.confidence()).isLessThan(SensitiveDataSanitizer.AUTO_THRESHOLD);
        });
        Map<?, ?> out = (Map<?, ?>) r.sanitized();
        assertThat(out.get("value")).isNotEqualTo("536904399");
        assertThat(out.get("invoice_no")).isEqualTo("123456789"); // identifier-like key: left alone
        assertThat(out.get("units")).isEqualTo(536904399);        // numbers are quantities, not ids
    }

    @Test
    void cardExpiryIsOnlyMaskedWhenThePanIsPresent() {
        var alone = sanitizer.inspect(Map.of("expiry", "12/28"));
        assertThat(alone.detections()).isEmpty();
        var withPan = sanitizer.inspect(Map.of("card", Map.of("number", "4111111111111111", "expiry", "12/28")));
        assertThat(withPan.detections()).extracting(Detection::type).contains(SensitiveType.PAN, SensitiveType.CARD_EXPIRY);
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) ((Map<String, Object>) withPan.sanitized()).get("card");
        assertThat(card.get("expiry")).isEqualTo("12/30");
    }

    @Test
    void freeTextIsScannedForEmbeddedValues() {
        var r = sanitizer.inspect(Map.of("note", "call jane@x.io re card 4111 1111 1111 1111, ssn 536-22-1987"));
        String text = (String) ((Map<?, ?>) r.sanitized()).get("note");
        assertThat(text).doesNotContain("jane@x.io").doesNotContain("4111 1111 1111 1111").doesNotContain("536-22-1987")
                .contains("@masked.com").contains("000-00-0000");
        assertThat(r.detections()).singleElement().satisfies(d -> assertThat(d.detector()).isEqualTo("free_text_scan"));
    }

    @Test
    void typesArePreservedAndNonSensitiveValuesUntouched() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("cvc", 123);
        in.put("amount", 100.0);
        in.put("count", 7);
        in.put("active", true);
        in.put("nothing", null);
        var r = sanitizer.inspect(in);
        Map<?, ?> out = (Map<?, ?>) r.sanitized();
        assertThat(out.get("cvc")).isEqualTo(0);
        assertThat(out.get("amount")).isEqualTo(100.0);
        assertThat(out.get("count")).isEqualTo(7);
        assertThat(out.get("active")).isEqualTo(true);
        assertThat(out.containsKey("nothing")).isTrue();
        assertThat(r.fieldsInspected()).isEqualTo(5);
    }

    @Test
    void emailPseudonymsAreStableWithinAnInstance() {
        var r = sanitizer.inspect(Map.of("a", "Jane@Example.com", "b", List.of("jane@example.com")));
        Map<?, ?> out = (Map<?, ?>) r.sanitized();
        assertThat(out.get("a")).isEqualTo(((List<?>) out.get("b")).get(0));
    }

    @Test
    void keyTokenisation() {
        assertThat(KeyClassifier.tokens("customerEmailAddress")).containsExactly("customer", "email", "address");
        assertThat(KeyClassifier.tokens("X-API-Key")).containsExactly("x", "api", "key");
        assertThat(KeyClassifier.tokens("APIKey")).containsExactly("api", "key");
        assertThat(KeyClassifier.tokens("card_CVV2")).containsExactly("card", "cvv2");
    }

    @Test
    void validators() {
        assertThat(ValueDetectors.luhn("4111111111111111")).isTrue();
        assertThat(ValueDetectors.luhn("4111111111111112")).isFalse();
        assertThat(ValueDetectors.cardBrand("378282246310005")).isEqualTo("AMEX");
        assertThat(ValueDetectors.validSsn("536221987")).isTrue();
        assertThat(ValueDetectors.validSsn("666221987")).isFalse();
        assertThat(ValueDetectors.validSsn("900221987")).isFalse();
        assertThat(ValueDetectors.isIban("DE89370400440532013000")).isTrue();
        assertThat(ValueDetectors.isIban("DE89370400440532013001")).isFalse();
        assertThat(ValueDetectors.isJwt("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc")).isTrue();
        assertThat(ValueDetectors.looksLikeRandomSecret("q8Z2xL0vN4mR7tY1wK9pB3sF6hJ5dA0e")).isTrue();
        assertThat(ValueDetectors.looksLikeRandomSecret("ThisIsJustACamelCaseSentence")).isFalse();
    }
}
