package com.svp.engine;

import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.model.DriftProfile;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.state.SessionStateStore;
import com.svp.engine.traffic.TrafficLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * JIT warm-up before the platform takes traffic (the app runs it before reporting ready).
 *
 * <p>Runs a representative request mix through a <em>private, throw-away</em> engine loaded with
 * the seed stubs, so class loading and JIT compilation of every code path (all state ops, every
 * matcher type, misses, drift) happen up front — without touching real sessions, stats or traffic.
 * The assignment measures latency "after a warm-up of at least 10,000 requests"; this makes that
 * the production behaviour too.
 */
public final class EngineWarmup {

    public static final int DEFAULT_REQUESTS = 10_000;

    private EngineWarmup() {
    }

    /** @return wall-clock milliseconds spent */
    public static long run(int requests) {
        long start = System.nanoTime();
        DriftPolicy drift = new DriftPolicy(() -> new SplittableRandom(42));
        drift.setRoute("POST /api/v1/orders", new DriftProfile(true, 0L, 0L, DriftProfile.Distribution.UNIFORM, 0.05, 503));
        StubMatchingEngine engine = new StubMatchingEngine(new StubRegistry(), SessionStateStore.withDefaults(), drift,
                new TrafficLog(256));
        engine.registry().upsertAll(SeedStubs.load());
        List<VirtualRequest> mix = mix();
        int sessionCount = 32;
        for (int i = 0; i < requests; i++) {
            VirtualRequest template = mix.get(i % mix.size());
            String session = "warmup-" + (i / mix.size()) % sessionCount;
            engine.matchAndExecute(new VirtualRequest(session, template.protocol(), template.method(), template.path(),
                    template.headers(), template.body(), template.query()));
            if (i % (mix.size() * sessionCount) == mix.size() * sessionCount - 1) {
                engine.sessions().list().forEach(s -> engine.sessions().delete(s.id()));
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static List<VirtualRequest> mix() {
        Map<String, String> json = Map.of("Content-Type", "application/json");
        List<VirtualRequest> m = new ArrayList<>();
        m.add(req("POST", "/api/v1/accounts", json, Map.of("account_holder", "W", "initial_deposit", 1.5, "currency", "USD")));
        m.add(req("POST", "/api/v1/accounts", Map.of(), Map.of("account_id", "ACC-W1", "account_holder", "W", "currency", "USD")));
        m.add(req("GET", "/api/v1/accounts/ACC-W1", Map.of(), null));
        m.add(req("GET", "/api/v1/accounts/ACC-1001", Map.of(), null));
        m.add(req("PUT", "/api/v1/accounts/ACC-W1", json, Map.of("status", "FROZEN")));
        m.add(req("GET", "/api/v1/accounts", Map.of(), null));
        m.add(req("GET", "/api/v1/accounts/summary", Map.of(), null));
        m.add(req("DELETE", "/api/v1/accounts/ACC-W1", Map.of(), null));
        m.add(req("GET", "/api/v1/accounts/ACC-W1", Map.of(), null));
        m.add(req("POST", "/api/v1/accounts", json, Map.of("currency", "EUR")));
        m.add(req("GET", "/api/v1/customers/C-1/accounts/ACC-2", Map.of(), null));
        m.add(req("GET", "/api/v1/health/?verbose=true", Map.of(), null));
        m.add(req("DELETE", "/api/v1/legacy/a/b/c", Map.of(), null));
        m.add(req("GET", "/api/v1/fx/USD-INR/rate", Map.of(), null));
        m.add(req("POST", "/api/v1/payments", json,
                Map.of("merchant_id", "MERCH_1092", "amount", 15.0, "card", Map.of("network", "VISA"))));
        m.add(req("POST", "/api/v1/payments", Map.of("content-type", "application/json; charset=UTF-8"),
                Map.of("merchant_id", "MERCH_9001", "amount", 10)));
        m.add(req("POST", "/api/v1/payments", json, Map.of("merchant_id", "MERCH_1", "amount", 20000)));
        m.add(req("POST", "/api/v1/payments", json,
                Map.of("merchant_id", "MERCH_1", "amount", 1, "beneficiary", Map.of("address", Map.of("country", "KP")))));
        m.add(req("POST", "/api/v1/payments", Map.of(), Map.of("amount", 1)));
        m.add(req("POST", "/api/v1/payments", Map.of("Content-Type", "text/plain"), Map.of("merchant_id", "M", "amount", 2)));
        m.add(req("GET", "/api/v1/payments/TXN-1001", Map.of(), null));
        m.add(req("POST", "/api/v1/orders", json, Map.of("items", List.of(Map.of("sku", "SKU-PREMIUM", "category", "A")))));
        m.add(req("POST", "/api/v1/orders", json, Map.of("items", List.of(Map.of("sku", "X", "category", "RESTRICTED")))));
        m.add(req("POST", "/api/v1/orders", json, Map.of("items", List.of())));
        m.add(req("GET", "/api/v1/secure/profile", Map.of("Authorization", "Bearer abc.def"), null));
        m.add(req("GET", "/api/v1/secure/profile", Map.of(), null));
        m.add(req("GET", "/api/v1/transactions?status=PENDING", Map.of(), null));
        m.add(req("GET", "/api/v1/transactions?status=SETTLED", Map.of(), null));
        m.add(req("GET", "/api/v1/offers", Map.of("x-channel", "MOBILE"), null));
        m.add(req("GET", "/api/v1/offers", Map.of(), null));
        m.add(req("GET", "/api/v2/does-not-exist", Map.of(), null));
        m.add(req("PATCH", "/api/v1/health", Map.of(), null));
        return m;
    }

    private static VirtualRequest req(String method, String path, Map<String, String> headers, Object body) {
        return new VirtualRequest(null, "HTTP", method, path, headers, body);
    }
}
