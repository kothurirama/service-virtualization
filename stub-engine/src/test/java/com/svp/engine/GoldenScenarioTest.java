package com.svp.engine;

import com.svp.engine.testsupport.EvalOutput;
import com.svp.engine.testsupport.MatchScenario;
import com.svp.engine.testsupport.ScenarioLoader;
import com.svp.engine.testsupport.ScenarioRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the 30 labelled scenarios (one JUnit case each) and writes target/eval/scenario-results.json.
 * Gate (QUALITY_CRITERIA 1.2/1.5): 30/30 pass, each &lt; 10 ms, mean &le; 5 ms.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenScenarioTest {

    private final List<ScenarioRunner.Result> results = Collections.synchronizedList(new ArrayList<>());
    private StubMatchingEngine engine;

    @BeforeAll
    void setUp() {
        // same JIT warm-up the application runs before it reports ready
        EngineWarmup.run(EngineWarmup.DEFAULT_REQUESTS);
        engine = TestEngines.seeded();
    }

    static List<MatchScenario> scenarios() {
        return ScenarioLoader.load();
    }

    @Test
    void suiteHasThirtyUniqueScenarios() {
        List<MatchScenario> all = scenarios();
        assertThat(all).hasSize(30);
        assertThat(all.stream().map(MatchScenario::id).distinct()).hasSize(30);
        assertThat(all.stream().map(MatchScenario::category).distinct())
                .contains("path_matching", "jsonpath", "state_mutation", "session_isolation", "fallback");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(MatchScenario scenario) {
        ScenarioRunner.Result result = ScenarioRunner.run(scenario, engine::matchAndExecute);
        results.add(result);
        assertThat(result.failures()).as(scenario.toString()).isEmpty();
        assertThat(result.engineLatencyMs()).as(scenario.id() + " engine latency").isLessThan(10.0);
    }

    @AfterAll
    void writeReport() {
        List<ScenarioRunner.Result> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        double sum = 0;
        double max = 0;
        int passed = 0;
        Map<String, int[]> byCategory = new TreeMap<>();
        for (ScenarioRunner.Result r : sorted) {
            sum += r.engineLatencyMs();
            max = Math.max(max, r.engineLatencyMs());
            passed += r.passed() ? 1 : 0;
            int[] c = byCategory.computeIfAbsent(r.category(), k -> new int[2]);
            c[0]++;
            c[1] += r.passed() ? 1 : 0;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenarios", sorted.size());
        report.put("passed", passed);
        report.put("mean_engine_latency_ms", sorted.isEmpty() ? 0 : sum / sorted.size());
        report.put("max_engine_latency_ms", max);
        Map<String, String> cat = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> cat.put(k, v[1] + "/" + v[0]));
        report.put("by_category", cat);
        report.put("results", sorted);
        report.put("hardware", EvalOutput.hardware());
        EvalOutput.write("scenario-results", report);
    }
}
