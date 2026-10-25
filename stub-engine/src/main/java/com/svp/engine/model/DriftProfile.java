package com.svp.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated downstream degradation: base latency, jitter and fault injection.
 * Missing fields take neutral defaults; a profile that is posted without {@code enabled} is enabled.
 */
public record DriftProfile(
        Boolean enabled,
        Long latencyMs,
        Long jitterMs,
        Distribution distribution,
        Double errorRate,
        Integer errorStatus) {

    public enum Distribution { FIXED, UNIFORM, GAUSSIAN }

    public static final long MAX_DELAY_MS = 60_000;

    public static final DriftProfile DISABLED = new DriftProfile(false, 0L, 0L, Distribution.UNIFORM, 0.0, 500);

    public DriftProfile {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        latencyMs = latencyMs == null ? 0L : latencyMs;
        jitterMs = jitterMs == null ? 0L : jitterMs;
        distribution = distribution == null ? Distribution.UNIFORM : distribution;
        errorRate = errorRate == null ? 0.0 : errorRate;
        errorStatus = errorStatus == null ? 500 : errorStatus;
    }

    /** @return human readable validation errors (empty when valid) */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (latencyMs < 0 || latencyMs > MAX_DELAY_MS) {
            errors.add("latency_ms must be between 0 and " + MAX_DELAY_MS);
        }
        if (jitterMs < 0 || jitterMs > MAX_DELAY_MS) {
            errors.add("jitter_ms must be between 0 and " + MAX_DELAY_MS);
        }
        if (errorRate.isNaN() || errorRate < 0.0 || errorRate > 1.0) {
            errors.add("error_rate must be between 0.0 and 1.0");
        }
        if (errorStatus < 400 || errorStatus > 599) {
            errors.add("error_status must be a 4xx or 5xx code");
        }
        return errors;
    }

    @JsonIgnore
    public boolean active() {
        return enabled && (latencyMs > 0 || jitterMs > 0 || errorRate > 0.0);
    }
}
