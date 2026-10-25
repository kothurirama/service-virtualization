package com.svp.recording;

import com.svp.engine.StubMatchingEngine;
import com.svp.engine.json.Json;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.recording.generate.ReviewItem;
import com.svp.recording.generate.StubConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerStubGeneratorTest {

    private final SanitizerStubGenerator sanitizerGenerator = new SanitizerStubGenerator();

    /** The assignment's example, verbatim in spirit. */
    @Test
    void sensitivePciCardDataIsRedactedInGeneratedStub() {
        RawTrafficLog log = new RawTrafficLog(
                "/api/v1/payments",
                Map.of("credit_card", "4111111111111111", "cvv", "123", "amount", 100.0));

        StubConfig config = sanitizerGenerator.generateFromLog(log);
        String generatedBody = config.virtualStubs().get(0).responseTemplate().body().toString();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) config.virtualStubs().get(0).responseTemplate().body();

        assertThat(generatedBody).doesNotContain("4111111111111111");
        assertThat(body.get("cvv")).isNotEqualTo("123");          // field-level check, not a substring check
        assertThat(body.get("amount")).isEqualTo(100.0);          // non-sensitive field must survive (precision)
        assertThat(config.sanitizationSummary().pciFieldsMasked()).isEqualTo(2);
        assertThat(config.sanitizationSummary().requiresHumanReview()).isFalse();
        assertThat(config.generatedBy()).isEqualTo("TrafficRecordingProxy");
        assertThat(config.stubConfigId()).startsWith("AUTO_GEN_PAYMENTS_");
    }

    @Test
    void maskedPanIsABrandMatchedLuhnValidTestCardKeepingLayout() {
        StubConfig config = sanitizerGenerator.generateFromLog(new RawTrafficLog("/api/v1/x",
                Map.of("card_number", "5555 5555 5555 4444", "amex", "378282246310005")));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) config.virtualStubs().get(0).responseTemplate().body();
        assertThat(body.get("card_number")).isEqualTo("5200 8282 8282 8210");
        assertThat(body.get("amex")).isEqualTo("371449635398431");
    }

    private static RawTrafficLog exchange(String method, String path, Map<String, String> reqHeaders, Object reqBody,
                                          int status, Map<String, String> resHeaders, Object resBody) {
        return new RawTrafficLog("HTTP", method, path, new RawMessage(null, reqHeaders, reqBody),
                new RawMessage(status, resHeaders, resBody), null);
    }

    @Test
    void generatesTemplatedReplayableStubsFromRecordedTraffic() {
        List<RawTrafficLog> logs = List.of(
                exchange("POST", "/api/v1/payments", Map.of("Content-Type", "application/json", "Authorization", "Bearer abcdefghijklmnop1234"),
                        Map.of("merchant_id", "MERCH_1092", "amount", 150.0, "currency", "USD",
                                "idempotency_key", "c1f0f6d2-8d1e-4b7e-9d1a-2f4b6c8d0e1f", "card", Map.of("number", "4111111111111111")),
                        200, Map.of("Content-Type", "application/json", "Set-Cookie", "sid=abc", "Date", "Sun, 25 Oct 2026"),
                        Map.of("transaction_id", "TXN-881920", "merchant_id", "MERCH_1092", "amount", 150.0,
                                "customer_email", "jane@example.com", "status", "SUCCESS")),
                exchange("GET", "/api/v1/accounts/ACC-9921", Map.of(), null, 200,
                        Map.of("Content-Type", "application/json", "Location", "/api/v1/accounts/ACC-9921"),
                        Map.of("account_id", "ACC-9921", "status", "ACTIVE", "note", "template {{injection}} attempt")),
                exchange("GET", "/api/v1/accounts/ACC-1234", Map.of(), null, 200, Map.of(),
                        Map.of("account_id", "ACC-1234", "status", "ACTIVE")));

        StubConfig config = sanitizerGenerator.generate("payment-svc", logs);
        assertThat(config.stubConfigId()).startsWith("AUTO_GEN_PAYMENT_SVC_");
        assertThat(config.virtualStubs()).hasSize(2); // the two account reads collapse into one templated stub

        StubDefinition pay = config.virtualStubs().get(0);
        assertThat(pay.stubId()).isEqualTo("rec_payment_svc_post_payments_001");
        assertThat(pay.source()).isEqualTo(StubSource.RECORDED);
        assertThat(pay.requestMatcher().bodyJsonpath())
                .containsEntry("$.merchant_id", "MERCH_1092").containsEntry("$.currency", "USD")
                .doesNotContainKey("$.idempotency_key");
        assertThat(pay.requestMatcher().headers()).containsEntry("Content-Type", "application/json").hasSize(1);
        assertThat(pay.responseTemplate().headers()).containsOnlyKeys("Content-Type");
        String payJson = Json.write(pay);
        assertThat(payJson).doesNotContain("jane@example.com").doesNotContain("4111111111111111")
                .contains("{{request.body.merchant_id}}").contains("{{request.body.amount}}");

        StubDefinition read = config.virtualStubs().get(1);
        assertThat(read.requestMatcher().path()).isEqualTo("/api/v1/accounts/{accountId}");
        assertThat(Json.write(read)).contains("{{request.path.accountId}}").contains("{ {injection}}");

        // the generated stubs actually serve traffic
        StubMatchingEngine engine = StubMatchingEngine.inMemory();
        engine.registry().upsertAll(config.virtualStubs());
        VirtualResponse r = engine.matchAndExecute(new VirtualRequest("x", "HTTP", "GET", "/api/v1/accounts/ACC-5555", Map.of(), null));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.bodyMap()).containsEntry("account_id", "ACC-5555");
        assertThat(r.headers()).containsEntry("Location", "/api/v1/accounts/ACC-5555");
        VirtualResponse p = engine.matchAndExecute(new VirtualRequest("x", "HTTP", "POST", "/api/v1/payments",
                Map.of("Content-Type", "application/json"), Map.of("merchant_id", "MERCH_1092", "currency", "USD", "amount", 75.25)));
        assertThat(p.bodyMap()).containsEntry("amount", 75.25).containsEntry("status", "SUCCESS");
    }

    @Test
    void lowConfidenceFindingsAndConflictsGoToTheReviewQueue() {
        List<RawTrafficLog> logs = List.of(
                exchange("POST", "/api/v1/integrations", Map.of("Content-Type", "application/json"),
                        Map.of("partner", "ACME", "config_blob", "q8Z2xL0vN4mR7tY1wK9pB3sF6hJ5dA0e"), 201, Map.of(), Map.of("ok", true)),
                exchange("POST", "/api/v1/integrations", Map.of("Content-Type", "application/json"),
                        Map.of("partner", "ACME"), 500, Map.of(), Map.of("ok", false)));
        StubConfig config = sanitizerGenerator.generate("integrations", logs);
        assertThat(config.sanitizationSummary().requiresHumanReview()).isTrue();
        assertThat(config.reviewQueue()).extracting(ReviewItem::fieldName)
                .contains("request.body.config_blob", "response.status_code");
        assertThat(config.reviewQueue()).filteredOn(i -> "request.body.config_blob".equals(i.fieldName()))
                .singleElement().satisfies(i -> {
                    assertThat(i.suggestedAction()).isEqualTo("REDACT");
                    assertThat(i.reason()).contains("high-entropy");
                });
        assertThat(Json.write(config)).doesNotContain("q8Z2xL0vN4mR7tY1wK9pB3sF6hJ5dA0e");
    }
}
