package com.svp.engine;

import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.state.SessionStateStore;
import com.svp.engine.traffic.TrafficLog;

/** Fresh engines for tests: seed stubs registered, nothing shared between tests. */
public final class TestEngines {

    private TestEngines() {
    }

    public static StubMatchingEngine seeded() {
        StubMatchingEngine engine = StubMatchingEngine.inMemory();
        engine.registry().upsertAll(SeedStubs.load());
        return engine;
    }

    public static StubMatchingEngine seeded(DriftPolicy drift) {
        StubMatchingEngine engine = new StubMatchingEngine(new StubRegistry(), SessionStateStore.withDefaults(), drift,
                new TrafficLog(2048));
        engine.registry().upsertAll(SeedStubs.load());
        return engine;
    }
}
