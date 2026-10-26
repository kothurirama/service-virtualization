package com.svp.recording;

import com.svp.engine.json.Json;
import com.svp.recording.sanitize.SensitiveDataSanitizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hold-out probe: 30 cases written <em>after</em> the detector and golden dataset were finished, to
 * check for over-fitting to our own data. Includes values embedded in free text, PANs as JSON numbers
 * and inside arrays, provider-prefixed keys, and look-alikes that must survive (Luhn-failing numbers,
 * UUIDs, hashes, a Luhn-valid 16-digit amount, last-four, token_type, MCC codes).
 */
class HoldoutProbeTest {

    private record Case(String key, Object value, boolean sensitive) {
    }

    private static final List<Case> CASES = List.of(
            new Case("notes", "Customer paid with card 4532 0151 1283 0366 yesterday", true),
            new Case("comment", "SSN on file is 536-90-4399, verify before payout", true),
            new Case("description", "contact me at priya.sharma@corp-mail.co.in please", true),
            new Case("pan_number", 5555555555554444L, true),
            new Case("acctRef", "6011000990139424", true),
            new Case("tags", List.of("vip", "4111-1111-1111-1111"), true),
            new Case("pwd", "Summer2026!", true),
            new Case("client_secret", "s3cr3t-value-123", true),
            new Case("x_api_key", "AKIAIOSFODNN7EXAMPLE", true),
            new Case("stripe_key", "sk_live_FAKEholdout000", true),
            new Case("session", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0."
                    + "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U", true),
            new Case("date_of_birth", "1988-04-17", true),
            new Case("mobile", "+91 98480 22338", true),
            new Case("taxpayer_id", "123-45-6789", true),
            new Case("iban", "GB82WEST12345698765432", true),
            new Case("home_address", "42 MG Road, Pragathi Nagar, Hyderabad 500090", true),
            new Case("value", "536904399", true),
            new Case("order_number", "4111111111111112", false),
            new Case("invoice_no", "123456789", false),
            new Case("request_id", "3f2b8c1e-9a7d-4b6e-8f10-2c3d4e5f6a7b", false),
            new Case("sha256", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", false),
            new Case("amount", 4111111111111111.0, false),
            new Case("phone_type", "MOBILE", false),
            new Case("token_type", "Bearer", false),
            new Case("email_verified", true, false),
            new Case("card_last_four", "1111", false),
            new Case("expires_in", 3600, false),
            new Case("merchant_category_code", "5411", false),
            new Case("timestamp", "2026-10-25T13:36:14Z", false),
            new Case("version", "1.2.3", false));

    @Test
    void holdoutCasesAreClassifiedCorrectly() {
        Map<String, Object> body = new LinkedHashMap<>();
        CASES.forEach(c -> body.put(c.key(), c.value()));
        Map<?, ?> out = (Map<?, ?>) new SensitiveDataSanitizer().inspect(body).sanitized();

        List<String> misses = new ArrayList<>();
        List<String> overMasked = new ArrayList<>();
        for (Case c : CASES) {
            boolean changed = !Json.write(out.get(c.key())).equals(Json.write(c.value()));
            if (c.sensitive() && !changed) {
                misses.add(c.key());
            }
            if (!c.sensitive() && changed) {
                overMasked.add(c.key() + " -> " + out.get(c.key()));
            }
        }
        assertThat(CASES).hasSize(30);
        assertThat(misses).as("sensitive values left verbatim").isEmpty();
        assertThat(overMasked).as("look-alikes altered").isEmpty();
    }
}
