package com.svp.engine.drift;

import com.svp.engine.StubMatchingEngine;
import com.svp.engine.TestEngines;
import com.svp.engine.model.DriftProfile;
import com.svp.engine.model.DriftProfile.Distribution;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.engine.testsupport.EvalOutput;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** QUALITY_CRITERIA 3: injected faults and latency match the configured distributions. */
class DriftPolicyTest {

    private static final int SAMPLES = 20_000;
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();

    private static DriftPolicy seeded(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        return new DriftPolicy(() -> random);
    }

    private static DriftProfile profile(long latency, long jitter, Distribution d, double errorRate) {
        return new DriftProfile(true, latency, jitter, d, errorRate, 500);
    }

    @Test
    void errorRateMatchesConfiguration() {
        for (double rate : new double[]{0.05, 0.10, 0.50}) {
            DriftPolicy policy = seeded(7);
            policy.setGlobal(profile(0, 0, Distribution.FIXED, rate));
            int faults = 0;
            for (int i = 0; i < SAMPLES; i++) {
                DriftPolicy.Decision d = policy.decide("GET /x");
                if (d.fault()) {
                    faults++;
                    assertThat(d.faultStatus()).isEqualTo(500);
                }
            }
            double observed = faults / (double) SAMPLES;
            REPORT.put("error_rate_" + rate, Map.of("configured", rate, "observed", observed, "samples", SAMPLES));
            assertThat(observed).as("configured %.2f", rate).isCloseTo(rate, within(0.01));
        }
    }

    @Test
    void uniformJitterStaysInBoundsAndCentresOnBase() {
        DriftPolicy policy = seeded(11);
        policy.setGlobal(profile(100, 20, Distribution.UNIFORM, 0));
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        double sum = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long d = policy.decide(null).delayMs();
            min = Math.min(min, d);
            max = Math.max(max, d);
            sum += d;
        }
        double mean = sum / SAMPLES;
        REPORT.put("uniform_100_pm_20", Map.of("min", min, "max", max, "mean", mean));
        assertThat(min).isGreaterThanOrEqualTo(80);
        assertThat(max).isLessThanOrEqualTo(120);
        assertThat(mean).isCloseTo(100.0, within(5.0));
    }

    @Test
    void gaussianJitterHasConfiguredMeanAndSpreadAndIsNeverNegative() {
        DriftPolicy policy = seeded(13);
        policy.setGlobal(profile(50, 10, Distribution.GAUSSIAN, 0));
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long d = policy.decide(null).delayMs();
            assertThat(d).isGreaterThanOrEqualTo(0);
            sum += d;
            sumSq += (double) d * d;
        }
        double mean = sum / SAMPLES;
        double std = Math.sqrt(sumSq / SAMPLES - mean * mean);
        REPORT.put("gaussian_50_sd_10", Map.of("mean", mean, "stddev", std));
        assertThat(mean).isCloseTo(50.0, within(2.5));
        assertThat(std).isCloseTo(10.0, within(1.0));
    }

    @Test
    void fixedLatencyIsExact() {
        DriftPolicy policy = seeded(1);
        policy.setGlobal(profile(250, 99, Distribution.FIXED, 0));
        for (int i = 0; i < 100; i++) {
            assertThat(policy.decide(null).delayMs()).isEqualTo(250);
        }
    }

    @Test
    void routeOverridesGlobalWhileEnabled() {
        DriftPolicy policy = seeded(3);
        policy.setGlobal(profile(10, 0, Distribution.FIXED, 0));
        policy.setRoute("POST /api/v1/payments", profile(900, 0, Distribution.FIXED, 0));
        assertThat(policy.decide("POST /api/v1/payments").delayMs()).isEqualTo(900);
        assertThat(policy.decide("GET /api/v1/health").delayMs()).isEqualTo(10);

        policy.setRoute("POST /api/v1/payments", new DriftProfile(false, 900L, 0L, Distribution.FIXED, 0.0, 500));
        assertThat(policy.decide("POST /api/v1/payments").delayMs()).isEqualTo(10);
        assertThat(policy.removeRoute("POST /api/v1/payments")).isTrue();
        policy.reset();
        assertThat(policy.decide("POST /api/v1/payments")).isEqualTo(DriftPolicy.Decision.NONE);
    }

    @Test
    void invalidProfilesAreRejected() {
        DriftPolicy policy = new DriftPolicy();
        assertThatThrownBy(() -> policy.setGlobal(profile(-1, 0, Distribution.FIXED, 0))).hasMessageContaining("latency_ms");
        assertThatThrownBy(() -> policy.setGlobal(profile(0, 0, Distribution.FIXED, 1.5))).hasMessageContaining("error_rate");
        assertThatThrownBy(() -> policy.setGlobal(new DriftProfile(true, 0L, 0L, null, 0.1, 302)))
                .hasMessageContaining("error_status");
        assertThatThrownBy(() -> policy.setRoute(" ", profile(0, 0, Distribution.FIXED, 0))).hasMessageContaining("route");
    }

    @Test
    void injectedFaultsNeverMutateStateAndDelayIsNotEngineLatency() {
        DriftPolicy policy = seeded(5);
        StubMatchingEngine engine = TestEngines.seeded(policy);
        policy.setRoute("POST /api/v1/accounts", new DriftProfile(true, 400L, 0L, Distribution.FIXED, 1.0, 503));

        VirtualResponse r = engine.matchAndExecute(new VirtualRequest("fault", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_id", "ACC-F", "currency", "USD")));
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.faultInjected()).isTrue();
        assertThat(r.stateMutated()).isFalse();
        assertThat(r.injectedDelayMs()).isEqualTo(400);
        assertThat(r.executionLatencyMs()).isLessThan(10.0);
        assertThat(r.matchedStubId()).isEqualTo("stub_account_create_v1");
        assertThat(engine.sessions().get("fault").orElseThrow().entryCount()).isZero();

        policy.removeRoute("POST /api/v1/accounts");
        assertThat(engine.matchAndExecute(new VirtualRequest("fault", "HTTP", "GET", "/api/v1/accounts/ACC-F",
                Map.of(), null)).statusCode()).isEqualTo(404);
    }

    @Test
    void engineLevelErrorRateOverManyRequests() {
        DriftPolicy policy = seeded(9);
        StubMatchingEngine engine = TestEngines.seeded(policy);
        policy.setGlobal(new DriftProfile(true, 0L, 0L, Distribution.FIXED, 0.10, 500));
        int faults = 0;
        for (int i = 0; i < SAMPLES; i++) {
            VirtualResponse r = engine.matchAndExecute(new VirtualRequest("e", "HTTP", "GET", "/api/v1/health", Map.of(), null));
            if (r.statusCode() == 500 && r.faultInjected()) {
                faults++;
            } else {
                assertThat(r.statusCode()).isEqualTo(200);
            }
        }
        double observed = faults / (double) SAMPLES;
        REPORT.put("engine_error_rate_0.10", Map.of("observed", observed, "samples", SAMPLES));
        assertThat(observed).isCloseTo(0.10, within(0.01));
    }

    @AfterAll
    static void writeReport() {
        EvalOutput.write("drift-results", REPORT);
    }
}
