package com.svp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svp.engine.drift.DriftPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every control-plane endpoint: happy path and invalid input (QUALITY_CRITERIA 4). */
@SpringBootTest
@AutoConfigureMockMvc
class ControlPlaneApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    DriftPolicy drift;

    @AfterEach
    void resetDrift() {
        drift.reset();
    }

    private ResultActions json(MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode read(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ engine contract

    @Test
    void executeEndpointHonoursTheAssignmentContract() throws Exception {
        json(post("/api/v1/engine/execute"), """
                {"session_id":"cp-exec","protocol":"HTTP","method":"POST","path":"/api/v1/accounts",
                 "headers":{"Content-Type":"application/json","X-Correlation-ID":"trace-abc-123"},
                 "body":{"account_holder":"Jane Doe","initial_deposit":500.00,"currency":"USD"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched_stub_id").value("stub_account_create_v1"))
                .andExpect(jsonPath("$.status_code").value(201))
                .andExpect(jsonPath("$.body.account_holder").value("Jane Doe"))
                .andExpect(jsonPath("$.body.balance").value(500.0))
                .andExpect(jsonPath("$.state_mutated").value(true))
                .andExpect(jsonPath("$.execution_latency_ms").isNumber())
                .andExpect(jsonPath("$.matched_rules", hasItem("jsonpath_body_check")));

        json(post("/api/v1/engine/execute"), """
                {"session_id":"cp-exec","method":"GET","path":"/nowhere"}""")
                .andExpect(jsonPath("$.matched_stub_id").doesNotExist())
                .andExpect(jsonPath("$.status_code").value(404));
    }

    // ------------------------------------------------------------------ stubs

    @Test
    void stubLifecycle() throws Exception {
        String stub = """
                {"stub_id":"cp_quote_v1","request_matcher":{"method":"GET","path":"/cp/quotes/{symbol}"},
                 "response_template":{"status_code":200,"body":{"symbol":"{{request.path.symbol}}","px":101.5}}}""";
        json(post("/api/v1/stubs"), stub).andExpect(status().isCreated())
                .andExpect(jsonPath("$.stub.stub_id").value("cp_quote_v1"))
                .andExpect(jsonPath("$.route").value("GET /cp/quotes/{symbol}"));
        json(post("/api/v1/stubs"), stub).andExpect(status().isOk()); // update

        mvc.perform(get("/virtual/cp/quotes/INFY").header("X-Session-Id", "cp-stubs"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-SVP-Matched-Stub", "cp_quote_v1"))
                .andExpect(jsonPath("$.symbol").value("INFY"));

        mvc.perform(get("/api/v1/stubs").param("q", "cp_quote")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].hit_count").value(1));

        json(patch("/api/v1/stubs/cp_quote_v1/enabled"), "{\"enabled\":false}")
                .andExpect(jsonPath("$.stub.enabled").value(false));
        mvc.perform(get("/virtual/cp/quotes/INFY")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_MATCHING_STUB"));
        mvc.perform(get("/api/v1/stubs").param("enabled", "false"))
                .andExpect(jsonPath("$[*].stub.stub_id", hasItem("cp_quote_v1")));

        json(put("/api/v1/stubs/cp_quote_v1"), stub.replace("101.5", "99.0"))
                .andExpect(jsonPath("$.stub.enabled").value(true));
        mvc.perform(get("/virtual/cp/quotes/TCS")).andExpect(jsonPath("$.px").value(99.0));

        mvc.perform(delete("/api/v1/stubs/cp_quote_v1")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/stubs/cp_quote_v1")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/stubs/cp_quote_v1")).andExpect(status().isNotFound());
    }

    @Test
    void invalidStubsAreRejectedWithEveryReason() throws Exception {
        json(post("/api/v1/stubs"), """
                {"stub_id":"cp_bad","request_matcher":{"method":"GET","path":"/x/{id}",
                  "body_jsonpath":{"$.items[?(@.x)]":1}},
                 "response_template":{"body":{"v":"{{request.path.nope}}"}}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_STUB"))
                .andExpect(jsonPath("$.details", hasSize(2)));
        json(post("/api/v1/stubs/validate"), """
                {"stub_id":"cp_ok","request_matcher":{"method":"GET","path":"/ok"},"response_template":{"body":{}}}""")
                .andExpect(jsonPath("$.valid").value(true));
        json(post("/api/v1/stubs"), "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_JSON"));
        json(put("/api/v1/stubs/a"), """
                {"stub_id":"b","request_matcher":{"method":"GET","path":"/b"},"response_template":{"body":{}}}""")
                .andExpect(status().isBadRequest());
        json(post("/api/v1/stubs"), "[{\"stub_id\":\"cp_batch_ok\",\"request_matcher\":{\"method\":\"GET\",\"path\":\"/b1\"},"
                + "\"response_template\":{\"body\":{}}},{\"stub_id\":\"cp_batch_bad\"}]").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stubs/cp_batch_ok")).andExpect(status().isNotFound()); // batch is atomic
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void sessionLifecycleAndStateInspection() throws Exception {
        json(post("/api/v1/sessions"), "{\"session_id\":\"cp-sess\",\"ttl_seconds\":120,\"labels\":{\"pipeline\":\"build-42\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ttl_seconds").value(120))
                .andExpect(jsonPath("$.labels.pipeline").value("build-42"));

        mvc.perform(post("/virtual/api/v1/accounts").header("X-Session-Id", "cp-sess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_id\":\"ACC-7\",\"account_holder\":\"Zoe\",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/ACC-7"));

        mvc.perform(get("/api/v1/sessions/cp-sess/state"))
                .andExpect(jsonPath("$.entry_count").value(1))
                .andExpect(jsonPath("$.collections.accounts.ACC-7.account_holder").value("Zoe"));
        mvc.perform(get("/api/v1/sessions")).andExpect(jsonPath("$[*].session_id", hasItem("cp-sess")));

        mvc.perform(delete("/api/v1/sessions/cp-sess/state")).andExpect(status().isNoContent());
        mvc.perform(get("/virtual/api/v1/accounts/ACC-7").header("X-Session-Id", "cp-sess"))
                .andExpect(status().isNotFound());

        json(post("/api/v1/sessions"), "{\"session_id\":\"cp-sess\",\"action\":\"teardown\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.torn_down").value(true));
        mvc.perform(get("/api/v1/sessions/cp-sess")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/sessions/cp-sess")).andExpect(status().isNotFound());

        JsonNode generated = read(mvc.perform(post("/api/v1/sessions")).andExpect(status().isCreated()));
        assertThat(generated.get("session_id").asText()).startsWith("sess-");
        json(post("/api/v1/sessions"), "{\"session_id\":\"bad id\"}").andExpect(status().isBadRequest());
        json(post("/api/v1/sessions"), "{\"action\":\"explode\"}").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ drift

    @Test
    void driftConfiguration() throws Exception {
        json(put("/api/v1/drift/global"), "{\"latency_ms\":5,\"jitter_ms\":1,\"distribution\":\"gaussian\",\"error_rate\":0.0}")
                .andExpect(jsonPath("$.global.enabled").value(true))
                .andExpect(jsonPath("$.global.distribution").value("GAUSSIAN"));
        json(put("/api/v1/drift/routes"), "{\"route\":\"POST /api/v1/payments\",\"error_rate\":1.0,\"error_status\":503}")
                .andExpect(jsonPath("$.routes['POST /api/v1/payments'].error_status").value(503))
                .andExpect(jsonPath("$.known_routes", hasItem("POST /api/v1/payments")));

        mvc.perform(post("/virtual/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
                .andExpect(status().is(503))
                .andExpect(header().string("X-SVP-Fault-Injected", "true"))
                .andExpect(jsonPath("$.error").value("INJECTED_FAULT"));

        mvc.perform(delete("/api/v1/drift/routes").param("route", "POST /api/v1/payments")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/drift/routes").param("route", "POST /api/v1/payments")).andExpect(status().isNotFound());
        json(put("/api/v1/drift/global"), "{\"error_rate\":2}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("error_rate")));
        mvc.perform(post("/api/v1/drift/reset")).andExpect(jsonPath("$.global.enabled").value(false));
    }

    // ------------------------------------------------------------------ traffic + metrics

    @Test
    void trafficViewerAndMetrics() throws Exception {
        mvc.perform(get("/virtual/api/v1/health").header("X-Session-Id", "cp-traffic")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/traffic").param("session_id", "cp-traffic").param("limit", "5"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].matched_stub_id").value("stub_health_v1"))
                .andExpect(jsonPath("$[0].path").value("/api/v1/health"));
        mvc.perform(get("/api/v1/traffic").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/metrics/summary"))
                .andExpect(jsonPath("$.traffic.total_requests").isNumber())
                .andExpect(jsonPath("$.stubs").isNumber());
    }

    // ------------------------------------------------------------------ recording

    @Test
    void ingestAutoPublishesCleanRecordingsAndServesThem() throws Exception {
        JsonNode res = read(json(post("/api/v1/recording/ingest"), """
                {"service_name":"cp-cards","logs":[{"method":"GET","path":"/cp/cards/CRD-9001",
                  "request":{"headers":{"Authorization":"Bearer abcdefghijklmnopqrstu"}},
                  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
                    "body":{"card_id":"CRD-9001","pan":"4111111111111111","holder_email":"x@y.com","limit":5000.0}}}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.generated_by").value("TrafficRecordingProxy"))
                .andExpect(jsonPath("$.sanitization_summary.pci_fields_masked").value(1))
                .andExpect(jsonPath("$.sanitization_summary.requires_human_review").value(false)));
        String stubId = res.get("published_stub_ids").get(0).asText();
        assertThat(res.toString()).doesNotContain("4111111111111111").doesNotContain("x@y.com");

        mvc.perform(get("/virtual/cp/cards/CRD-1234"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-SVP-Matched-Stub", stubId))
                .andExpect(jsonPath("$.card_id").value("CRD-1234"))
                .andExpect(jsonPath("$.pan").value("4242424242424242"))
                .andExpect(jsonPath("$.limit").value(5000.0));
    }

    @Test
    void suspiciousRecordingsWaitForApproval() throws Exception {
        JsonNode res = read(json(post("/api/v1/recording/ingest"), """
                [{"method":"POST","path":"/cp/integrations",
                  "request":{"headers":{"Content-Type":"application/json"},
                    "body":{"partner":"ACME","blob":"q8Z2xL0vN4mR7tY1wK9pB3sF6hJ5dA0e"}},
                  "response":{"status_code":201,"body":{"ok":true}}}]""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.review_queue[0].field_name").value("request.body.blob"))
                .andExpect(jsonPath("$.review_queue[0].suggested_action").value("REDACT")));
        String id = res.get("stub_config_id").asText();
        mvc.perform(post("/virtual/cp/integrations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()); // not live yet

        mvc.perform(post("/api/v1/recording/configs/" + id + "/approve")).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(post("/virtual/cp/integrations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/recording/configs/" + id + "/reject")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/recording/configs")).andExpect(jsonPath("$[*].stub_config_id", hasItem(id)));
        mvc.perform(get("/api/v1/recording/configs/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void draftsAndRejection() throws Exception {
        JsonNode res = read(json(post("/api/v1/recording/ingest"), """
                {"auto_publish":false,"logs":[{"method":"GET","path":"/cp/draft","response":{"status_code":200,"body":{"a":1}}}]}""")
                .andExpect(jsonPath("$.status").value("DRAFT")));
        String id = res.get("stub_config_id").asText();
        mvc.perform(post("/api/v1/recording/configs/" + id + "/reject")).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/v1/recording/configs/" + id + "/approve")).andExpect(status().isConflict());
        json(post("/api/v1/recording/ingest"), "{\"logs\":[]}").andExpect(status().isBadRequest());
        json(post("/api/v1/recording/ingest"), "[{\"path\":\"/x\"}]").andExpect(status().isBadRequest());
    }

    @Test
    void recordedStubsNeverOverwriteHandWrittenOnes() throws Exception {
        json(post("/api/v1/stubs"), """
                {"stub_id":"rec_cp_guard_get_cp_guard_001","request_matcher":{"method":"GET","path":"/cp/guard"},
                 "response_template":{"body":{"hand":"written"}}}""").andExpect(status().isCreated());
        json(post("/api/v1/recording/ingest"), """
                {"service_name":"cp-guard","logs":[{"method":"GET","path":"/cp/guard","response":{"status_code":200,"body":{"x":1}}}]}""")
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.status_reason", containsString("never overwrite")));
        mvc.perform(get("/virtual/cp/guard")).andExpect(jsonPath("$.hand").value("written"));
    }

    @Test
    void sanitizeEndpoints() throws Exception {
        json(post("/api/v1/recording/sanitize"), "{\"ssn\":\"536-22-1987\",\"amount\":10.5,\"note\":\"call x@y.io\"}")
                .andExpect(jsonPath("$.sanitized.ssn").value("000-00-0000"))
                .andExpect(jsonPath("$.sanitized.amount").value(10.5))
                .andExpect(jsonPath("$.detections", hasSize(2)))
                .andExpect(jsonPath("$.fields_inspected").value(3));
        json(post("/api/v1/recording/sanitize-log"), """
                {"method":"GET","path":"/cards/4111111111111111?email=a%40b.com",
                 "request":{"headers":{"Cookie":"sid=abc123"}},"response":{"status_code":200,"body":{"ok":true}}}""")
                .andExpect(jsonPath("$.path").value("/cards/4242424242424242"))
                .andExpect(jsonPath("$.query.email", containsString("@masked.com")))
                .andExpect(jsonPath("$.request_headers.Cookie").value("[REDACTED]"));
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
