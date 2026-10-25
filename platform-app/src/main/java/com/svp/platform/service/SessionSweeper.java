package com.svp.platform.service;

import com.svp.engine.state.SessionStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Evicts sessions idle for longer than their TTL, so abandoned CI runs cannot leak memory. */
@Component
public class SessionSweeper {

    private static final Logger log = LoggerFactory.getLogger(SessionSweeper.class);

    private final SessionStateStore sessions;

    public SessionSweeper(SessionStateStore sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${svp.sessions.sweep-interval:30s}")
    public void sweep() {
        int evicted = sessions.evictExpired();
        if (evicted > 0) {
            log.info("Evicted {} idle sessions", evicted);
        }
    }
}
