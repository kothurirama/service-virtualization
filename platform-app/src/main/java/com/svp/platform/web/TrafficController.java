package com.svp.platform.web;

import com.svp.engine.StubRegistry;
import com.svp.engine.state.SessionStateStore;
import com.svp.engine.traffic.TrafficLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.List;

/** Recent virtual traffic (ring buffer) and live engine metrics. */
@RestController
@RequestMapping("/api/v1")
public class TrafficController {

    public record MetricsView(TrafficLog.Summary traffic, int stubs, int enabledStubs, int sessions,
                              long uptimeSeconds) {
    }

    private final TrafficLog traffic;
    private final StubRegistry registry;
    private final SessionStateStore sessions;

    public TrafficController(TrafficLog traffic, StubRegistry registry, SessionStateStore sessions) {
        this.traffic = traffic;
        this.registry = registry;
        this.sessions = sessions;
    }

    @GetMapping("/traffic")
    public List<TrafficLog.Entry> recent(@RequestParam(name = "limit", defaultValue = "100") int limit,
                                         @RequestParam(name = "session_id", required = false) String sessionId,
                                         @RequestParam(name = "stub_id", required = false) String stubId,
                                         @RequestParam(name = "after_seq", defaultValue = "-1") long afterSeq) {
        if (limit < 1 || limit > 2000) {
            throw new IllegalArgumentException("limit must be between 1 and 2000");
        }
        return traffic.recent(limit, blankToNull(sessionId), blankToNull(stubId), afterSeq);
    }

    @DeleteMapping("/traffic")
    public ResponseEntity<Void> clear() {
        traffic.clear();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metrics/summary")
    public MetricsView metrics() {
        return new MetricsView(traffic.summary(), registry.size(), registry.index().size(), sessions.size(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
