package com.svp.engine;

import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.engine.testsupport.EvalOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QUALITY_CRITERIA 1.4 gate: many CI sessions run concurrently and write the SAME entity ids with
 * session-specific payloads. Operations of one session are spread over many threads (parallel test
 * steps), and operations of different sessions interleave freely. Any cross-session read, lost
 * write or wrong 404/200 is a violation; the gate is zero violations.
 */
class ConcurrentSessionIsolationTest {

    private static final int SESSIONS = 64;
    private static final int THREADS = 16;
    private static final int IDS = 200;

    private final StubMatchingEngine engine = TestEngines.seeded();
    private final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
    private final AtomicLong operations = new AtomicLong();

    private VirtualResponse call(String session, String method, String path, Object body) {
        operations.incrementAndGet();
        return engine.matchAndExecute(new VirtualRequest(session, "HTTP", method, path, Map.of(), body));
    }

    private static String session(int s) {
        return "ci-run-" + s;
    }

    private static String holder(int s, int i) {
        return "owner-of-" + session(s) + "-" + i;
    }

    private void runShuffled(List<Runnable> tasks, long seed) throws Exception {
        Collections.shuffle(tasks, new Random(seed));
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Runnable t : tasks) {
                futures.add(pool.submit(t));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void conflictingIdsAcrossConcurrentSessionsNeverLeak() throws Exception {
        long start = System.nanoTime();

        // phase 1: every session creates ACC-0..ACC-199 (identical ids everywhere)
        List<Runnable> creates = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            for (int i = 0; i < IDS; i++) {
                int fs = s;
                int fi = i;
                creates.add(() -> {
                    VirtualResponse r = call(session(fs), "POST", "/api/v1/accounts",
                            Map.of("account_id", "ACC-" + fi, "account_holder", holder(fs, fi), "currency", "USD",
                                    "initial_deposit", fs * 1000 + fi));
                    if (r.statusCode() != 201) {
                        violations.add("create " + session(fs) + "/" + fi + " -> " + r.statusCode());
                    }
                });
            }
        }
        runShuffled(creates, 1);

        // phase 2: concurrent reads + merges + deletes of even ids, interleaved
        List<Runnable> mixed = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            for (int i = 0; i < IDS; i++) {
                int fs = s;
                int fi = i;
                mixed.add(() -> {
                    VirtualResponse r = call(session(fs), "GET", "/api/v1/accounts/ACC-" + fi, null);
                    // a concurrent delete of an even id may legitimately win the race; nothing else may differ
                    if (r.statusCode() == 200) {
                        Object h = r.bodyMap().get("account_holder");
                        if (!holder(fs, fi).equals(h)) {
                            violations.add("read " + session(fs) + "/ACC-" + fi + " saw " + h);
                        }
                    } else if (fi % 2 != 0) {
                        violations.add("read " + session(fs) + "/ACC-" + fi + " -> " + r.statusCode());
                    }
                });
                if (fi % 2 == 1) {
                    mixed.add(() -> {
                        VirtualResponse r = call(session(fs), "PUT", "/api/v1/accounts/ACC-" + fi,
                                Map.of("status", "FROZEN-" + session(fs)));
                        if (r.statusCode() != 200 || !holder(fs, fi).equals(r.bodyMap().get("account_holder"))) {
                            violations.add("merge " + session(fs) + "/ACC-" + fi + " -> " + r.statusCode() + " " + r.body());
                        }
                    });
                } else {
                    mixed.add(() -> {
                        VirtualResponse r = call(session(fs), "DELETE", "/api/v1/accounts/ACC-" + fi, null);
                        if (r.statusCode() != 204) {
                            violations.add("delete " + session(fs) + "/ACC-" + fi + " -> " + r.statusCode());
                        }
                    });
                }
            }
        }
        runShuffled(mixed, 2);

        // phase 3: verify final state of every session independently
        for (int s = 0; s < SESSIONS; s++) {
            for (int i = 0; i < IDS; i++) {
                VirtualResponse r = call(session(s), "GET", "/api/v1/accounts/ACC-" + i, null);
                if (i % 2 == 0) {
                    if (r.statusCode() != 404) {
                        violations.add("deleted " + session(s) + "/ACC-" + i + " still visible");
                    }
                } else {
                    Map<String, Object> b = r.bodyMap();
                    if (r.statusCode() != 200 || !holder(s, i).equals(b.get("account_holder"))
                            || !("FROZEN-" + session(s)).equals(b.get("status"))
                            || ((Number) b.get("balance")).intValue() != s * 1000 + i) {
                        violations.add("final " + session(s) + "/ACC-" + i + " -> " + r.statusCode() + " " + b);
                    }
                }
            }
            assertThat(engine.sessions().get(session(s)).orElseThrow().entryCount()).isEqualTo(IDS / 2);
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sessions", SESSIONS);
        report.put("threads", THREADS);
        report.put("entity_ids_per_session_shared_across_sessions", IDS);
        report.put("operations", operations.get());
        report.put("violations", violations.size());
        report.put("first_violations", violations.stream().limit(10).toList());
        report.put("elapsed_s", seconds);
        report.put("hardware", EvalOutput.hardware());
        EvalOutput.write("isolation-results", report);

        assertThat(violations).as("isolation violations").isEmpty();
    }

    @Test
    void tearingDownOneSessionUnderLoadDoesNotAffectOthers() throws Exception {
        for (int s = 0; s < 8; s++) {
            call(session(s), "POST", "/api/v1/accounts", Map.of("account_id", "K", "account_holder", holder(s, 0), "currency", "USD"));
        }
        List<Runnable> tasks = new ArrayList<>();
        for (int n = 0; n < 4000; n++) {
            int s = 1 + n % 7;
            tasks.add(() -> {
                VirtualResponse r = call(session(s), "GET", "/api/v1/accounts/K", null);
                if (r.statusCode() != 200 || !holder(s, 0).equals(r.bodyMap().get("account_holder"))) {
                    violations.add("survivor " + session(s) + " -> " + r.statusCode());
                }
            });
        }
        tasks.add(() -> engine.sessions().delete(session(0)));
        runShuffled(tasks, 3);
        assertThat(violations).isEmpty();
        assertThat(call(session(0), "GET", "/api/v1/accounts/K", null).statusCode()).isEqualTo(404);
    }
}
