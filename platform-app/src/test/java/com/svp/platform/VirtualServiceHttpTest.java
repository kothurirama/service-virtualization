package com.svp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.json.Json;
import com.svp.engine.model.DriftProfile;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.engine.testsupport.MatchScenario;
import com.svp.engine.testsupport.ScenarioLoader;
import com.svp.engine.testsupport.ScenarioRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** End-to-end over a real socket: what a CI pipeline actually experiences. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VirtualServiceHttpTest {

    @LocalServerPort
    int port;
    @Autowired
    DriftPolicy drift;
    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void resetDrift() {
        drift.reset();
    }

    private HttpResponse<String> send(String method, String path, String session, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (session != null) {
            b.header("X-Session-Id", session);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void workedExampleOverHttp() throws Exception {
        HttpResponse<String> created = send("POST", "/virtual/api/v1/accounts", "http-A",
                "{\"account_holder\":\"Jane Doe\",\"initial_deposit\":500.00,\"currency\":\"USD\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("X-SVP-Matched-Stub")).hasValue("stub_account_create_v1");
        String id = mapper.readTree(created.body()).get("account_id").asText();

        HttpResponse<String> readA = send("GET", "/virtual/api/v1/accounts/" + id, "http-A", null);
        assertThat(readA.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(readA.body()).get("account_holder").asText()).isEqualTo("Jane Doe");

        HttpResponse<String> readB = send("GET", "/virtual/api/v1/accounts/" + id, "http-B", null);
        assertThat(readB.statusCode()).isEqualTo(404);

        HttpResponse<String> viaQuery = send("GET", "/virtual/api/v1/accounts/" + id + "?svp_session=http-A", null, null);
        assertThat(viaQuery.statusCode()).isEqualTo(200);
    }

    /** The 30 labelled scenarios, through the public REST contract instead of in-process. */
    @Test
    void goldenScenariosPassThroughTheExecuteApi() {
        List<String> failures = new ArrayList<>();
        for (MatchScenario s : ScenarioLoader.load()) {
            ScenarioRunner.Result r = ScenarioRunner.run(s, req -> {
                HttpResponse<String> res = send("POST", "/api/v1/engine/execute", null, Json.write(namespaced(req)));
                return Json.read(res.body(), VirtualResponse.class);
            });
            if (!r.passed()) {
                failures.add(r.id() + " " + r.failures());
            }
        }
        assertThat(failures).isEmpty();
    }

    /** Scenario session ids are suffixed so this run cannot collide with other tests in the same JVM. */
    private static VirtualRequest namespaced(VirtualRequest r) {
        return new VirtualRequest(r.sessionId() + "-http", r.protocol(), r.method(), r.path(), r.headers(), r.body(), r.query());
    }

    @Test
    void parallelPipelinesOverHttpDoNotPolluteEachOther() throws Exception {
        int sessions = 16;
        int ids = 40;
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int s = 0; s < sessions; s++) {
                String session = "http-par-" + s;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < ids; i++) {
                        String body = "{\"account_id\":\"ACC-" + i + "\",\"account_holder\":\"" + session + "\",\"currency\":\"USD\"}";
                        if (send("POST", "/virtual/api/v1/accounts", session, body).statusCode() != 201) {
                            violations.add(session + " create " + i);
                        }
                    }
                    for (int i = 0; i < ids; i++) {
                        HttpResponse<String> r = send("GET", "/virtual/api/v1/accounts/ACC-" + i, session, null);
                        try {
                            JsonNode n = mapper.readTree(r.body());
                            if (r.statusCode() != 200 || !session.equals(n.get("account_holder").asText())) {
                                violations.add(session + " read " + i + " -> " + r.body());
                            }
                        } catch (IOException e) {
                            violations.add(session + " unreadable " + r.body());
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void injectedLatencyIsAppliedOnTheWire() {
        drift.setRoute("GET /api/v1/health", new DriftProfile(true, 120L, 0L, DriftProfile.Distribution.FIXED, 0.0, 500));
        long start = System.nanoTime();
        HttpResponse<String> r = send("GET", "/virtual/api/v1/health", "http-lat", null);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(ms).isGreaterThanOrEqualTo(120);
        double engineMs = Double.parseDouble(r.headers().firstValue("X-SVP-Engine-Latency-Ms").orElseThrow());
        assertThat(engineMs).isLessThan(10.0); // injected delay is not engine latency
    }

    @Test
    void configuredErrorRateHoldsOverHttp() throws Exception {
        drift.setGlobal(new DriftProfile(true, 0L, 0L, DriftProfile.Distribution.FIXED, 0.10, 500));
        int n = 3000;
        AtomicInteger faults = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    HttpResponse<String> r = send("GET", "/virtual/api/v1/health", "http-err", null);
                    if (r.statusCode() == 500 && r.headers().firstValue("X-SVP-Fault-Injected").isPresent()) {
                        faults.incrementAndGet();
                    } else if (r.statusCode() != 200) {
                        unexpected.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(unexpected.get()).isZero();
        // binomial sd at n=3000, p=0.1 is 0.55 pp; +-2.5 pp is > 4 sd
        assertThat(faults.get() / (double) n).isCloseTo(0.10, within(0.025));
    }

    @Test
    void nonJsonBodiesAndUnknownRoutes() {
        HttpResponse<String> r = send("POST", "/virtual/does/not/exist", "http-x", null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("NO_MATCHING_STUB");
    }
}
