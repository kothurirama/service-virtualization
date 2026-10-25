package com.svp.engine.testsupport;

import com.svp.engine.model.VirtualRequest;

import java.util.List;
import java.util.Map;

/** One labelled scenario from {@code stub_match_scenarios.json}. */
public record MatchScenario(
        String id,
        String name,
        String category,
        List<VirtualRequest> setup,
        VirtualRequest request,
        Expectation expect) {

    public MatchScenario {
        setup = setup == null ? List.of() : setup;
    }

    public record Expectation(
            String matchedStubId,
            Integer statusCode,
            Boolean stateMutated,
            List<String> matchedRules,
            Map<String, Object> body) {
    }

    @Override
    public String toString() {
        return id + " " + name;
    }
}
