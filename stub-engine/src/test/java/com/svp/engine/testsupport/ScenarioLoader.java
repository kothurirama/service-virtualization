package com.svp.engine.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.svp.engine.json.Json;

import java.io.InputStream;
import java.util.List;

/** Loads labelled scenario suites from the test classpath. */
public final class ScenarioLoader {

    public static final String DEFAULT_SUITE = "stub_match_scenarios.json";

    private record Suite(String suite, String description, List<MatchScenario> scenarios) {
    }

    private ScenarioLoader() {
    }

    public static List<MatchScenario> load(String resource) {
        InputStream in = ScenarioLoader.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("scenario suite not found on classpath: " + resource);
        }
        return Json.read(in, new TypeReference<Suite>() { }).scenarios();
    }

    public static List<MatchScenario> load() {
        return load(DEFAULT_SUITE);
    }
}
