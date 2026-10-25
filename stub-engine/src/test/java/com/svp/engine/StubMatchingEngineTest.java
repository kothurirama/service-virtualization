package com.svp.engine;

import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.engine.testsupport.MatchScenario;
import com.svp.engine.testsupport.ScenarioLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The three behaviours from the assignment's illustrative JUnit example, against the real engine. */
class StubMatchingEngineTest {

    private StubMatchingEngine matchingEngine;

    /** Same warm-up the application runs before it reports ready (see EngineWarmup). */
    @BeforeAll
    static void warmUp() {
        EngineWarmup.run(EngineWarmup.DEFAULT_REQUESTS);
    }

    @BeforeEach
    void setUp() {
        matchingEngine = TestEngines.seeded();
    }

    @Test
    void staticHttpHeaderAndBodyMatch() {
        VirtualRequest request = new VirtualRequest(
                "session-001", "HTTP", "POST", "/api/v1/accounts",
                Map.of("Content-Type", "application/json"),
                Map.of("initial_deposit", 500.00, "currency", "USD", "account_holder", "Jane Doe"));

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.executionLatencyMs()).isLessThan(10.0);
        assertThat(response.matchedStubId()).isEqualTo("stub_account_create_v1");
        assertThat(response.matchedRules()).containsExactly("path_exact", "jsonpath_body_check");
        assertThat(response.stateMutated()).isTrue();
        assertThat(response.bodyMap()).containsEntry("balance", 500.0).containsEntry("status", "ACTIVE");
        assertThat(response.headers()).containsEntry("Location", "/api/v1/accounts/ACC-1001");
    }

    @Test
    void dynamicStateIsIsolatedToSession() {
        // Step 1: create entity in session A (client-supplied id)
        VirtualRequest createReqSessionA = new VirtualRequest(
                "session-A", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_id", "ACC-100", "account_holder", "Alice", "currency", "USD"));
        assertThat(matchingEngine.matchAndExecute(createReqSessionA).statusCode()).isEqualTo(201);

        // Step 2: query entity in session A -> exists
        VirtualRequest getReqSessionA = new VirtualRequest(
                "session-A", "HTTP", "GET", "/api/v1/accounts/ACC-100", Map.of(), null);
        VirtualResponse resA = matchingEngine.matchAndExecute(getReqSessionA);
        assertThat(resA.statusCode()).isEqualTo(200);
        assertThat(resA.bodyMap().get("account_holder")).isEqualTo("Alice");

        // Step 3: query entity in session B -> isolated (404)
        VirtualRequest getReqSessionB = new VirtualRequest(
                "session-B", "HTTP", "GET", "/api/v1/accounts/ACC-100", Map.of(), null);
        VirtualResponse resB = matchingEngine.matchAndExecute(getReqSessionB);
        assertThat(resB.statusCode()).isEqualTo(404);
        assertThat(resB.stateMutated()).isFalse();
    }

    @Test
    void evaluateLatencyThresholdOverGoldenSet() {
        List<MatchScenario> scenarios = ScenarioLoader.load("stub_match_scenarios.json");
        assertThat(scenarios).hasSize(30);
        long startTime = System.nanoTime();

        for (MatchScenario scenario : scenarios) {
            scenario.setup().forEach(matchingEngine::matchAndExecute);
            VirtualResponse res = matchingEngine.matchAndExecute(scenario.request());
            assertThat(res.executionLatencyMs()).as(scenario.id()).isLessThan(10.0);
        }

        double totalDurationMs = (System.nanoTime() - startTime) / 1_000_000.0;
        double avgLatency = totalDurationMs / scenarios.size();
        assertThat(avgLatency).isLessThanOrEqualTo(5.0);
    }

    @Test
    void missReturnsDiagnosticsInsteadOfThrowing() {
        VirtualResponse res = matchingEngine.matchAndExecute(new VirtualRequest(
                "s", "HTTP", "GET", "/api/v1/secure/profile", Map.of(), null));
        assertThat(res.matchedStubId()).isEqualTo("stub_profile_unauthorized_v1");

        VirtualResponse miss = matchingEngine.matchAndExecute(new VirtualRequest(
                "s", "HTTP", "GET", "/nope", Map.of(), null));
        assertThat(miss.matchedStubId()).isNull();
        assertThat(miss.statusCode()).isEqualTo(404);
        assertThat(miss.bodyMap()).containsEntry("error", "NO_MATCHING_STUB");
    }

    @Test
    void invalidRequestIsA400NotAnException() {
        VirtualResponse res = matchingEngine.matchAndExecute(new VirtualRequest(
                "s", "HTTP", null, "/x", Map.of(), null));
        assertThat(res.statusCode()).isEqualTo(400);
        VirtualResponse badSession = matchingEngine.matchAndExecute(new VirtualRequest(
                "bad session id with spaces", "HTTP", "GET", "/api/v1/health", Map.of(), null));
        assertThat(badSession.statusCode()).isEqualTo(400);
    }

    @Test
    void missingSessionIdUsesTheDefaultSession() {
        matchingEngine.matchAndExecute(new VirtualRequest(null, "HTTP", "POST", "/api/v1/accounts", Map.of(),
                Map.of("account_id", "ACC-D", "currency", "USD")));
        VirtualResponse res = matchingEngine.matchAndExecute(new VirtualRequest(null, null, "get",
                "/api/v1/accounts/ACC-D", null, null));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.sessionId()).isEqualTo("default");
    }
}
