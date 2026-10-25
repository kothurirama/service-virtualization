package com.svp.engine.match;

import com.svp.engine.StubMatchingEngine;
import com.svp.engine.json.Json;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.VirtualRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic precedence rules from QUALITY_CRITERIA 1.5. */
class PathPrecedenceTest {

    private StubMatchingEngine engine;

    static StubDefinition stub(String id, String method, String path, int priority, String extraMatcher) {
        return Json.read("""
                {"stub_id":"%s","priority":%d,
                 "request_matcher":{"method":"%s","path":"%s"%s},
                 "response_template":{"status_code":200,"body":{"id":"%s"}}}
                """.formatted(id, priority, method, path, extraMatcher, id), StubDefinition.class);
    }

    String match(String method, String path) {
        return match(method, path, Map.of(), null);
    }

    String match(String method, String path, Map<String, String> headers, Object body) {
        return engine.matchAndExecute(new VirtualRequest("p", "HTTP", method, path, headers, body)).matchedStubId();
    }

    @BeforeEach
    void setUp() {
        engine = StubMatchingEngine.inMemory();
        engine.registry().upsert(stub("tail", "GET", "/a/**", 0, ""));
        engine.registry().upsert(stub("wildcard", "GET", "/a/*/c", 0, ""));
        engine.registry().upsert(stub("variable", "GET", "/a/{x}/c", 0, ""));
        engine.registry().upsert(stub("literal", "GET", "/a/b/c", 0, ""));
        engine.registry().upsert(stub("any-method", "*", "/a/b/c", 100, ""));
        engine.registry().upsert(stub("root", "GET", "/", 0, ""));
    }

    @Test
    void literalBeatsVariableBeatsWildcardBeatsTail() {
        assertThat(match("GET", "/a/b/c")).isEqualTo("literal");
        assertThat(match("GET", "/a/zz/c")).isEqualTo("variable");
        engine.registry().delete("variable");
        assertThat(match("GET", "/a/zz/c")).isEqualTo("wildcard");
        engine.registry().delete("wildcard");
        assertThat(match("GET", "/a/zz/c")).isEqualTo("tail");
    }

    @Test
    void tailMatchesZeroOrMoreSegments() {
        assertThat(match("GET", "/a")).isEqualTo("tail");
        assertThat(match("GET", "/a/1/2/3/4/5")).isEqualTo("tail");
        assertThat(match("GET", "/b")).isNull();
    }

    @Test
    void specificMethodBeatsAnyMethodEvenWithLowerPriority() {
        assertThat(match("GET", "/a/b/c")).isEqualTo("literal");
        assertThat(match("POST", "/a/b/c")).isEqualTo("any-method");
    }

    @Test
    void rootAndSlashNormalisation() {
        assertThat(match("GET", "/")).isEqualTo("root");
        assertThat(match("GET", "//a//b/c/")).isEqualTo("literal");
        assertThat(match("GET", "/a/b/c?x=1")).isEqualTo("literal");
    }

    @Test
    void sameRouteOrderedByPriorityThenConstraintCountThenRegistrationOrder() {
        engine.registry().upsert(stub("p0-first", "POST", "/r", 0, ""));
        engine.registry().upsert(stub("p0-second", "POST", "/r", 0, ""));
        assertThat(match("POST", "/r")).isEqualTo("p0-first");

        engine.registry().upsert(stub("p0-constrained", "POST", "/r", 0, ",\"headers\":{\"X-A\":\"1\"}"));
        assertThat(match("POST", "/r", Map.of("X-A", "1"), null)).isEqualTo("p0-constrained");
        assertThat(match("POST", "/r", Map.of("X-A", "2"), null)).isEqualTo("p0-first");

        engine.registry().upsert(stub("p5", "POST", "/r", 5, ""));
        assertThat(match("POST", "/r", Map.of("X-A", "1"), null)).isEqualTo("p5");
    }

    @Test
    void failedConstraintOnSpecificBranchFallsBackToLessSpecificBranch() {
        engine.registry().upsert(stub("literal", "GET", "/a/b/c", 0, ",\"headers\":{\"X-Need\":\"yes\"}"));
        assertThat(match("GET", "/a/b/c")).isEqualTo("variable");
        assertThat(match("GET", "/a/b/c", Map.of("X-Need", "yes"), null)).isEqualTo("literal");
    }

    @Test
    void nearMissesExplainWhyAStubDidNotMatch() {
        engine.registry().clear();
        engine.registry().upsert(stub("needs-header", "GET", "/n", 0, ",\"headers\":{\"X-Tenant\":\"acme\"}"));
        var res = engine.matchAndExecute(new VirtualRequest("p", "HTTP", "GET", "/n", Map.of("X-Tenant", "other"), null));
        assertThat(res.matchedStubId()).isNull();
        assertThat(res.nearMisses()).singleElement().satisfies(nm -> {
            assertThat(nm.stubId()).isEqualTo("needs-header");
            assertThat(nm.failedRule()).isEqualTo("header_constraints");
            assertThat(nm.detail()).contains("X-Tenant").contains("other");
        });
    }
}
