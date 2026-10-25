package com.svp.engine.drift;

import com.svp.engine.model.DriftProfile;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Decides, per request, how much latency to inject and whether to inject a fault. A route profile
 * (keyed by "METHOD /path/pattern") overrides the global profile while it is enabled.
 *
 * <p>The engine only <em>decides</em>; the transport layer applies the delay (on a virtual thread),
 * so injected latency never shows up as engine latency and never blocks a platform thread.
 */
public final class DriftPolicy {

    public record Decision(long delayMs, boolean fault, int faultStatus) {
        public static final Decision NONE = new Decision(0, false, 0);
    }

    private final Supplier<RandomGenerator> random;
    private final ConcurrentHashMap<String, DriftProfile> routes = new ConcurrentHashMap<>();
    private volatile DriftProfile global = DriftProfile.DISABLED;

    public DriftPolicy() {
        this(ThreadLocalRandom::current);
    }

    /** @param random source of randomness (tests pass a seeded generator for reproducible statistics) */
    public DriftPolicy(Supplier<RandomGenerator> random) {
        this.random = random;
    }

    public Decision decide(String routeKey) {
        DriftProfile p = routeKey == null ? null : routes.get(routeKey);
        if (p == null || !p.enabled()) {
            p = global;
        }
        if (!p.active()) {
            return Decision.NONE;
        }
        RandomGenerator r = random.get();
        boolean fault = p.errorRate() > 0.0 && r.nextDouble() < p.errorRate();
        return new Decision(sampleDelay(p, r), fault, fault ? p.errorStatus() : 0);
    }

    static long sampleDelay(DriftProfile p, RandomGenerator r) {
        long base = p.latencyMs();
        long jitter = p.jitterMs();
        double sample = switch (p.distribution()) {
            case FIXED -> base;
            case UNIFORM -> jitter == 0 ? base : base + (r.nextDouble() * 2.0 - 1.0) * jitter;
            case GAUSSIAN -> jitter == 0 ? base : base + r.nextGaussian() * jitter;
        };
        long delay = Math.round(sample);
        return Math.max(0, Math.min(delay, DriftProfile.MAX_DELAY_MS));
    }

    public DriftProfile global() {
        return global;
    }

    public void setGlobal(DriftProfile profile) {
        validate(profile);
        this.global = profile;
    }

    public void setRoute(String routeKey, DriftProfile profile) {
        if (routeKey == null || routeKey.isBlank()) {
            throw new IllegalArgumentException("route is required, e.g. \"POST /api/v1/payments\"");
        }
        validate(profile);
        routes.put(routeKey.trim(), profile);
    }

    public boolean removeRoute(String routeKey) {
        return routeKey != null && routes.remove(routeKey.trim()) != null;
    }

    public Map<String, DriftProfile> routes() {
        return new TreeMap<>(routes);
    }

    public void reset() {
        routes.clear();
        global = DriftProfile.DISABLED;
    }

    private static void validate(DriftProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("drift profile is required");
        }
        List<String> errors = profile.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }
}
