package com.svp.platform.config;

import com.svp.engine.EngineWarmup;
import com.svp.engine.SeedStubs;
import com.svp.engine.StubRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs before the application reports ready (readiness flips after all runners complete):
 * 1) JIT warm-up of the engine on a private instance, 2) seed stubs.
 */
@Component
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final StubRegistry registry;
    private final SvpProperties props;

    public StartupInitializer(StubRegistry registry, SvpProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.warmup().requests() > 0) {
            long ms = EngineWarmup.run(props.warmup().requests());
            log.info("Engine warm-up: {} requests in {} ms", props.warmup().requests(), ms);
        }
        if (props.seed().enabled()) {
            int n = registry.upsertAll(SeedStubs.load(props.seed().location())).size();
            log.info("Seeded {} stubs from {}", n, props.seed().location());
        }
    }
}
