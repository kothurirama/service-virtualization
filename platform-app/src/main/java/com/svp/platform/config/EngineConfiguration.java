package com.svp.platform.config;

import com.svp.engine.StubMatchingEngine;
import com.svp.engine.StubRegistry;
import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.state.SessionStateStore;
import com.svp.engine.traffic.TrafficLog;
import com.svp.recording.SanitizerStubGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The engine is framework-free; Spring only wires it. */
@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    StubRegistry stubRegistry(Clock clock) {
        return new StubRegistry(clock);
    }

    @Bean
    SessionStateStore sessionStateStore(Clock clock, SvpProperties props) {
        SvpProperties.Sessions s = props.sessions();
        return new SessionStateStore(clock, s.defaultTtl(), s.maxSessions(), s.maxEntriesPerSession());
    }

    @Bean
    DriftPolicy driftPolicy() {
        return new DriftPolicy();
    }

    @Bean
    TrafficLog trafficLog(SvpProperties props) {
        return new TrafficLog(props.traffic().capacity());
    }

    @Bean
    StubMatchingEngine stubMatchingEngine(StubRegistry registry, SessionStateStore sessions, DriftPolicy drift,
                                          TrafficLog traffic) {
        return new StubMatchingEngine(registry, sessions, drift, traffic);
    }

    @Bean
    SanitizerStubGenerator sanitizerStubGenerator() {
        return new SanitizerStubGenerator();
    }
}
