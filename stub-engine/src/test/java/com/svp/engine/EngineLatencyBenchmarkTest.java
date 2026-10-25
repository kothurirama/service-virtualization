package com.svp.engine;

import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.engine.testsupport.EvalOutput;
import com.svp.engine.traffic.TrafficLog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine latency benchmark (QUALITY_CRITERIA 1.2). Runs in its own surefire execution without the
 * coverage agent ({@code @Tag("benchmark")}), after the standard 10,000-request warm-up.
 *
 * <p>Mixed workload over 64 sessions: 50% state reads, 25% creates, 10% merges/deletes,
 * 15% stateless matches (array JSONPath, header regex, query) and misses. Measured with more
 * threads than cores so scheduling contention is included in the percentiles.
 */
@Tag("benchmark")
class EngineLatencyBenchmarkTest {

    private static final int SESSIONS = 64;
    private static final int PRELOADED_PER_SESSION = 100;
    /** Oversubscribed on purpose: twice the cores (at least 4), so scheduling contention is measured. */
    private static final int THREADS = Math.max(4, 2 * Runtime.getRuntime().availableProcessors());
    private static final int MEASURED = 400_000;
    private static final int SINGLE_THREAD_OPS = 200_000;

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    private static VirtualRequest next(SplittableRandom r, int seq) {
        String session = "bench-" + r.nextInt(SESSIONS);
        double p = r.nextDouble();
        int k = r.nextInt(PRELOADED_PER_SESSION);
        if (p < 0.50) {
            return new VirtualRequest(session, "HTTP", "GET", "/api/v1/accounts/ACC-P" + k, Map.of(), null);
        }
        if (p < 0.75) {
            return new VirtualRequest(session, "HTTP", "POST", "/api/v1/accounts", JSON,
                    Map.of("account_holder", "Bench " + seq, "initial_deposit", 100.0, "currency", "USD"));
        }
        if (p < 0.82) {
            return new VirtualRequest(session, "HTTP", "PUT", "/api/v1/accounts/ACC-P" + k, JSON, Map.of("last_seq", seq));
        }
        if (p < 0.85) {
            return new VirtualRequest(session, "HTTP", "DELETE", "/api/v1/accounts/ACC-" + (1001 + r.nextInt(5000)), Map.of(), null);
        }
        if (p < 0.90) {
            return new VirtualRequest(session, "HTTP", "POST", "/api/v1/orders", JSON,
                    Map.of("items", List.of(Map.of("sku", "SKU-" + k, "category", "BOOKS"), Map.of("sku", "SKU-PREMIUM", "category", "X"))));
        }
        if (p < 0.94) {
            return new VirtualRequest(session, "HTTP", "GET", "/api/v1/secure/profile", Map.of("Authorization", "Bearer t" + k), null);
        }
        if (p < 0.97) {
            return new VirtualRequest(session, "HTTP", "GET", "/api/v1/transactions?status=PENDING", Map.of(), null);
        }
        return new VirtualRequest(session, "HTTP", "GET", "/api/v2/unknown/" + k, Map.of(), null);
    }

    private static StubMatchingEngine preparedEngine() {
        StubMatchingEngine engine = TestEngines.seeded();
        for (int s = 0; s < SESSIONS; s++) {
            for (int k = 0; k < PRELOADED_PER_SESSION; k++) {
                engine.matchAndExecute(new VirtualRequest("bench-" + s, "HTTP", "POST", "/api/v1/accounts", JSON,
                        Map.of("account_id", "ACC-P" + k, "account_holder", "Holder " + k, "currency", "USD")));
            }
        }
        // 10k warm-up on this engine's own workload (in addition to the shared EngineWarmup)
        SplittableRandom warm = new SplittableRandom(99);
        for (int i = 0; i < 10_000; i++) {
            engine.matchAndExecute(next(warm, i));
        }
        return engine;
    }

    @Test
    void engineMeetsLatencyAndCapacityGates() throws Exception {
        long warmupMs = EngineWarmup.run(EngineWarmup.DEFAULT_REQUESTS);
        StubMatchingEngine engine = preparedEngine();

        // 1) single-thread capacity (what one core can do)
        SplittableRandom single = new SplittableRandom(1);
        long t0 = System.nanoTime();
        for (int i = 0; i < SINGLE_THREAD_OPS; i++) {
            engine.matchAndExecute(next(single, i));
        }
        double singleThreadOpsPerSec = SINGLE_THREAD_OPS / ((System.nanoTime() - t0) / 1e9);

        // 2) contended run: more threads than cores
        double[][] perThread = new double[THREADS][MEASURED / THREADS];
        AtomicInteger statusErrors = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            Thread th = Thread.ofPlatform().name("bench-" + t).start(() -> {
                SplittableRandom r = new SplittableRandom(1000 + id);
                double[] out = perThread[id];
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < out.length; i++) {
                    VirtualResponse res = engine.matchAndExecute(next(r, i));
                    out[i] = res.executionLatencyMs();
                    if (res.statusCode() >= 500) {
                        statusErrors.incrementAndGet();
                    }
                }
            });
            threads.add(th);
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Thread th : threads) {
            th.join();
        }
        double wallSeconds = (System.nanoTime() - start) / 1e9;

        double[] all = Arrays.stream(perThread).flatMapToDouble(Arrays::stream).toArray();
        Arrays.sort(all);
        long under10 = Arrays.stream(all).filter(v -> v < 10.0).count();
        double passRate = under10 / (double) all.length;
        int cores = Runtime.getRuntime().availableProcessors();
        double contendedOpsPerSec = all.length / wallSeconds;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("warmup_requests", EngineWarmup.DEFAULT_REQUESTS + 10_000);
        report.put("warmup_ms", warmupMs);
        report.put("measured_requests", all.length);
        report.put("threads", THREADS);
        report.put("sessions", SESSIONS);
        report.put("p50_ms", TrafficLog.percentile(all, 50));
        report.put("p95_ms", TrafficLog.percentile(all, 95));
        report.put("p99_ms", TrafficLog.percentile(all, 99));
        report.put("p999_ms", TrafficLog.percentile(all, 99.9));
        report.put("max_ms", all[all.length - 1]);
        report.put("mean_ms", Arrays.stream(all).average().orElse(0));
        report.put("sub_10ms_pass_rate", passRate);
        report.put("unexpected_5xx", statusErrors.get());
        report.put("contended_ops_per_sec", contendedOpsPerSec);
        report.put("single_thread_ops_per_sec", singleThreadOpsPerSec);
        report.put("hardware", EvalOutput.hardware());
        EvalOutput.write("engine-latency", report);
        System.out.println("ENGINE BENCHMARK " + report);

        assertThat(statusErrors.get()).isZero();
        assertThat(TrafficLog.percentile(all, 99)).as("p99 engine latency (ms)").isLessThan(10.0);
        assertThat(passRate).as("sub-10ms pass rate").isGreaterThanOrEqualTo(0.999);
        assertThat(singleThreadOpsPerSec).as("ops/s on one core").isGreaterThanOrEqualTo(50_000);
        assertThat(cores).isPositive();
    }
}
