package com.svp.engine;

import com.svp.engine.json.Json;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;
import com.svp.engine.model.VirtualRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubRegistryTest {

    private final StubMatchingEngine engine = StubMatchingEngine.inMemory();

    private static StubDefinition def(String json) {
        return Json.read(json, StubDefinition.class);
    }

    private static final String VALID = """
            {"stub_id":"s1","request_matcher":{"method":"GET","path":"/x/{id}"},
             "response_template":{"status_code":200,"body":{"id":"{{request.path.id}}"}}}""";

    @Test
    void registersAndServesAValidStub() {
        engine.registry().upsert(def(VALID));
        var res = engine.matchAndExecute(new VirtualRequest("a", "HTTP", "GET", "/x/42", Map.of(), null));
        assertThat(res.bodyMap()).containsEntry("id", "42");
        assertThat(engine.registry().get("s1")).get().satisfies(r -> {
            assertThat(r.definition().source()).isEqualTo(StubSource.MANUAL);
            assertThat(r.compiled().stats().hits()).isEqualTo(1);
        });
    }

    @Test
    void reportsEveryProblemInOneValidationError() {
        StubDefinition bad = def("""
                {"stub_id":"bad one!","request_matcher":{"method":"FETCH","path":"/a/{x}/b?q=1",
                  "headers":{"Authorization":{"matches":"(oops"}},
                  "body_jsonpath":{"currency":"USD"}},
                 "response_template":{"status_code":999,"body":{"v":"{{request.nope}}"}},
                 "state_action":{"op":"UPSERT","collection":"c","key":"k"}}""");
        assertThatThrownBy(() -> engine.registry().upsert(bad))
                .isInstanceOfSatisfying(StubValidationException.class, e -> assertThat(e.errors())
                        .anySatisfy(m -> assertThat(m).contains("stub_id"))
                        .anySatisfy(m -> assertThat(m).contains("method"))
                        .anySatisfy(m -> assertThat(m).contains("query string"))
                        .anySatisfy(m -> assertThat(m).contains("invalid regex"))
                        .anySatisfy(m -> assertThat(m).contains("must start with '$'"))
                        .anySatisfy(m -> assertThat(m).contains("status_code"))
                        .anySatisfy(m -> assertThat(m).contains("unknown template expression"))
                        .anySatisfy(m -> assertThat(m).contains("state_action.op")));
    }

    @Test
    void rejectsReferencesThatCannotBeResolved() {
        assertThatThrownBy(() -> engine.registry().upsert(def("""
                {"stub_id":"s","request_matcher":{"method":"GET","path":"/a/{id}"},
                 "response_template":{"body":{"x":"{{request.path.accountId}}"}}}""")))
                .hasMessageContaining("unknown path variable 'accountId'");
        assertThatThrownBy(() -> engine.registry().upsert(def("""
                {"stub_id":"s","request_matcher":{"protocol":"GRPC","method":"POST","path":"/a"},
                 "response_template":{"body":{}}}""")))
                .hasMessageContaining("not supported");
    }

    @Test
    void batchIsAllOrNothing() {
        StubDefinition ok = def(VALID);
        StubDefinition broken = def("""
                {"stub_id":"s2","request_matcher":{"method":"GET"},"response_template":{"body":{}}}""");
        assertThatThrownBy(() -> engine.registry().upsertAll(List.of(ok, broken)))
                .isInstanceOf(StubValidationException.class);
        assertThat(engine.registry().size()).isZero();
        assertThat(engine.registry().index().size()).isZero();
    }

    @Test
    void toggleRemovesFromIndexWithoutDeleting() {
        engine.registry().upsert(def(VALID));
        engine.registry().setEnabled("s1", false);
        var miss = engine.matchAndExecute(new VirtualRequest("a", "HTTP", "GET", "/x/1", Map.of(), null));
        assertThat(miss.matchedStubId()).isNull();
        assertThat(engine.registry().get("s1")).isPresent();
        engine.registry().setEnabled("s1", true);
        assertThat(engine.matchAndExecute(new VirtualRequest("a", "HTTP", "GET", "/x/1", Map.of(), null))
                .matchedStubId()).isEqualTo("s1");
    }

    @Test
    void updateReplacesAndKeepsHitStatistics() {
        engine.registry().upsert(def(VALID));
        engine.matchAndExecute(new VirtualRequest("a", "HTTP", "GET", "/x/1", Map.of(), null));
        engine.registry().upsert(def(VALID.replace("\"status_code\":200", "\"status_code\":202")));
        var res = engine.matchAndExecute(new VirtualRequest("a", "HTTP", "GET", "/x/1", Map.of(), null));
        assertThat(res.statusCode()).isEqualTo(202);
        assertThat(engine.registry().get("s1").orElseThrow().compiled().stats().hits()).isEqualTo(2);
    }

    @Test
    void searchMatchesIdRouteTagsAndDescription() {
        engine.registry().upsertAll(SeedStubs.load());
        assertThat(engine.registry().search("payments", null, null)).extracting(StubRegistry.StubRecord::id)
                .contains("stub_payment_visa_v1", "stub_payment_get_v1");
        assertThat(engine.registry().search("POST /api/v1/orders", null, null)).hasSize(3);
        assertThat(engine.registry().search("", null, StubSource.SEED)).hasSize(engine.registry().size());
        engine.registry().setEnabled("stub_health_v1", false);
        assertThat(engine.registry().search(null, false, null)).extracting(StubRegistry.StubRecord::id)
                .containsExactly("stub_health_v1");
        assertThat(engine.registry().routeKeys()).contains("POST /api/v1/payments", "GET /api/v1/accounts/{id}");
    }
}
