package com.svp.engine.state;

import com.svp.engine.StubMatchingEngine;
import com.svp.engine.SeedStubs;
import com.svp.engine.StubRegistry;
import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.traffic.TrafficLog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStateStoreTest {

    /** Clock the test can move forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void idleSessionsExpireAfterTheirTtl() {
        MutableClock clock = new MutableClock();
        SessionStateStore store = new SessionStateStore(clock, Duration.ofMinutes(30), 100, 100);
        store.create("short", Duration.ofMinutes(1), Map.of("pipeline", "build-7"));
        SessionState longLived = store.getOrCreate("long");
        clock.advance(Duration.ofMinutes(5));
        longLived.touch(clock.millis());
        assertThat(store.evictExpired()).isEqualTo(1);
        assertThat(store.get("short")).isEmpty();
        assertThat(store.get("long")).isPresent();
    }

    @Test
    void zeroTtlNeverExpires() {
        MutableClock clock = new MutableClock();
        SessionStateStore store = new SessionStateStore(clock, Duration.ofMinutes(30), 100, 100);
        store.create("pinned", Duration.ZERO, Map.of());
        clock.advance(Duration.ofDays(3));
        assertThat(store.evictExpired()).isZero();
    }

    @Test
    void sessionAndEntryLimitsFailFast() {
        SessionStateStore store = new SessionStateStore(Clock.systemUTC(), Duration.ofMinutes(30), 2, 3);
        store.getOrCreate("a");
        store.getOrCreate("b");
        assertThatThrownBy(() -> store.getOrCreate("c")).isInstanceOf(SessionLimitException.class);

        SessionState a = store.getOrCreate("a");
        a.put("x", "1", 1);
        a.put("x", "2", 2);
        a.put("x", "3", 3);
        a.put("x", "3", 33); // overwrite does not count
        assertThatThrownBy(() -> a.put("x", "4", 4)).isInstanceOf(SessionLimitException.class);
        a.remove("x", "1");
        a.put("x", "4", 4);
        assertThat(a.entryCount()).isEqualTo(3);
    }

    @Test
    void engineSurfacesLimitsAs429() {
        SessionStateStore store = new SessionStateStore(Clock.systemUTC(), Duration.ofMinutes(30), 1, 1);
        StubMatchingEngine engine = new StubMatchingEngine(new StubRegistry(), store, new DriftPolicy(), new TrafficLog(64));
        engine.registry().upsertAll(SeedStubs.load());
        Map<String, Object> body = Map.of("currency", "USD");
        assertThat(engine.matchAndExecute(new VirtualRequest("one", "HTTP", "POST", "/api/v1/accounts", Map.of(), body))
                .statusCode()).isEqualTo(201);
        assertThat(engine.matchAndExecute(new VirtualRequest("one", "HTTP", "POST", "/api/v1/accounts", Map.of(), body))
                .statusCode()).isEqualTo(429);
        assertThat(engine.matchAndExecute(new VirtualRequest("two", "HTTP", "GET", "/api/v1/health", Map.of(), null))
                .statusCode()).isEqualTo(429);
    }

    @Test
    void teardownRemovesAllStateAndResetsIdSequence() {
        SessionStateStore store = SessionStateStore.withDefaults();
        SessionState s = store.getOrCreate("ci-1");
        s.put("accounts", "A", Map.of("x", 1));
        assertThat(s.nextId("ACC")).isEqualTo("ACC-1001");
        assertThat(store.delete("ci-1")).isTrue();
        SessionState again = store.getOrCreate("ci-1");
        assertThat(again.get("accounts", "A")).isNull();
        assertThat(again.nextId("ACC")).isEqualTo("ACC-1001");
        assertThat(store.delete("never-existed")).isFalse();
    }

    @Test
    void sessionIdsAreValidated() {
        SessionStateStore store = SessionStateStore.withDefaults();
        assertThatThrownBy(() -> store.getOrCreate("has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create("x".repeat(200), null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.getOrCreate("cicd-runner-9842:job_1.2").id()).isEqualTo("cicd-runner-9842:job_1.2");
    }
}
