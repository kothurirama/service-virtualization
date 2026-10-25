package com.svp.engine.testsupport;

import com.svp.engine.json.JsonValues;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Executes a scenario through any transport (in-process engine or HTTP) and checks it against its
 * label. Returns every mismatch instead of failing fast, so reports show the full picture.
 */
public final class ScenarioRunner {

    public record Result(String id, String category, String name, boolean passed, List<String> failures,
                         double engineLatencyMs, String matchedStubId, int statusCode) {
    }

    private ScenarioRunner() {
    }

    public static Result run(MatchScenario scenario, Function<VirtualRequest, VirtualResponse> executor) {
        for (VirtualRequest step : scenario.setup()) {
            executor.apply(step);
        }
        VirtualResponse response = executor.apply(scenario.request());
        List<String> failures = check(scenario.expect(), response);
        return new Result(scenario.id(), scenario.category(), scenario.name(), failures.isEmpty(), failures,
                response.executionLatencyMs(), response.matchedStubId(), response.statusCode());
    }

    public static List<String> check(MatchScenario.Expectation expect, VirtualResponse actual) {
        List<String> failures = new ArrayList<>();
        if (!Objects.equals(expect.matchedStubId(), actual.matchedStubId())) {
            failures.add("matched_stub_id: expected " + expect.matchedStubId() + " but was " + actual.matchedStubId());
        }
        if (expect.statusCode() != null && expect.statusCode() != actual.statusCode()) {
            failures.add("status_code: expected " + expect.statusCode() + " but was " + actual.statusCode());
        }
        if (expect.stateMutated() != null && expect.stateMutated() != actual.stateMutated()) {
            failures.add("state_mutated: expected " + expect.stateMutated() + " but was " + actual.stateMutated());
        }
        if (expect.matchedRules() != null && !expect.matchedRules().equals(actual.matchedRules())) {
            failures.add("matched_rules: expected " + expect.matchedRules() + " but was " + actual.matchedRules());
        }
        if (expect.body() != null) {
            subset("body", expect.body(), actual.body(), failures);
        }
        return failures;
    }

    /** Every key in {@code expected} must be present in {@code actual} with an equal value (recursively). */
    public static void subset(String path, Object expected, Object actual, List<String> failures) {
        if (expected instanceof Map<?, ?> exp) {
            if (!(actual instanceof Map<?, ?> act)) {
                failures.add(path + ": expected an object but was " + actual);
                return;
            }
            for (Map.Entry<?, ?> e : exp.entrySet()) {
                String child = path + "." + e.getKey();
                if (!act.containsKey(e.getKey())) {
                    failures.add(child + ": missing");
                } else {
                    subset(child, e.getValue(), act.get(e.getKey()), failures);
                }
            }
            return;
        }
        if (!JsonValues.jsonEquals(expected, actual)) {
            failures.add(path + ": expected " + expected + " but was " + actual);
        }
    }
}
