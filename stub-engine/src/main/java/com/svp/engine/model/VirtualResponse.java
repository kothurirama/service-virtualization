package com.svp.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a request against the stub set. {@code execution_latency_ms} is time spent
 * inside the engine only; {@code injected_delay_ms} is simulated network drift applied afterwards.
 */
public record VirtualResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) String matchedStubId,
        int statusCode,
        Map<String, String> headers,
        Object body,
        boolean stateMutated,
        double executionLatencyMs,
        List<String> matchedRules,
        String sessionId,
        long injectedDelayMs,
        boolean faultInjected,
        List<NearMiss> nearMisses) {

    /** Convenience view of an object body (null when the body is not a JSON object). */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> bodyMap() {
        return body instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @JsonIgnore
    public boolean matched() {
        return matchedStubId != null;
    }
}
