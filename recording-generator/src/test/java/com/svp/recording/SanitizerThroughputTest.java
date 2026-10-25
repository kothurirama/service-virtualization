package com.svp.recording;

import com.svp.engine.json.Json;
import com.svp.engine.testsupport.EvalOutput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** QUALITY_CRITERIA 2: >= 5,000 typical (~2 KB) logs per second per core. */
@Tag("benchmark")
class SanitizerThroughputTest {

    @Test
    void sanitisesThousandsOfLogsPerSecondOnOneCore() {
        List<RawTrafficLog> corpus = new ArrayList<>();
        for (GoldenDataset.Record r : GoldenDataset.load()) {
            corpus.add(r.log());
        }
        double avgBytes = corpus.stream().mapToInt(l -> Json.write(l).length()).average().orElse(0);
        SanitizerStubGenerator pipeline = new SanitizerStubGenerator();
        for (int i = 0; i < 20_000; i++) {
            pipeline.sanitize(corpus.get(i % corpus.size()));
        }
        int n = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            pipeline.sanitize(corpus.get(i % corpus.size()));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double perSecond = n / seconds;

        // end-to-end: sanitise + synthesise + compile stubs, in batches of 40 exchanges
        long genStart = System.nanoTime();
        int batches = 500;
        for (int i = 0; i < batches; i++) {
            pipeline.generate("bench", corpus);
        }
        double generateLogsPerSecond = batches * corpus.size() / ((System.nanoTime() - genStart) / 1e9);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("logs", n);
        report.put("avg_log_bytes", avgBytes);
        report.put("sanitize_logs_per_sec_single_thread", perSecond);
        report.put("sanitize_and_generate_logs_per_sec_single_thread", generateLogsPerSecond);
        report.put("hardware", EvalOutput.hardware());
        EvalOutput.write("sanitizer-throughput", report);
        System.out.println("SANITIZER THROUGHPUT " + report);

        assertThat(perSecond).isGreaterThanOrEqualTo(5_000);
    }
}
