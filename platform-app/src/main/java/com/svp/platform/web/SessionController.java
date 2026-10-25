package com.svp.platform.web;

import com.svp.engine.state.SessionState;
import com.svp.engine.state.SessionStateStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ephemeral test sessions. A CI pipeline creates one at start, sends {@code X-Session-Id} with
 * every virtual call, and tears it down at the end (idle sessions also expire on their TTL).
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    public record SessionCommand(String sessionId, Long ttlSeconds, Map<String, String> labels, String action) {
    }

    public record SessionView(String sessionId, Instant createdAt, Instant lastAccessAt, long ttlSeconds,
                              boolean explicit, Map<String, String> labels, int entryCount, long requestCount,
                              long mutationCount, Map<String, Integer> collections) {

        static SessionView of(SessionState s) {
            return new SessionView(s.id(), s.createdAt(), s.lastAccessAt(), s.ttl().toSeconds(), s.explicit(),
                    s.labels(), s.entryCount(), s.requestCount(), s.mutationCount(), s.collectionSizes());
        }
    }

    public record StateView(String sessionId, int entryCount, Map<String, Map<String, Object>> collections) {
    }

    private final SessionStateStore sessions;

    public SessionController(SessionStateStore sessions) {
        this.sessions = sessions;
    }

    /**
     * Create (default) or tear down ({@code "action": "teardown"}) a session. The body is optional;
     * without a {@code session_id} a random one is generated.
     */
    @PostMapping
    public ResponseEntity<Object> createOrTeardown(@RequestBody(required = false) SessionCommand cmd) {
        SessionCommand c = cmd == null ? new SessionCommand(null, null, null, null) : cmd;
        if ("teardown".equalsIgnoreCase(c.action()) || "delete".equalsIgnoreCase(c.action())) {
            if (c.sessionId() == null) {
                throw new IllegalArgumentException("session_id is required for teardown");
            }
            if (!sessions.delete(c.sessionId())) {
                throw new NotFoundException("no session '" + c.sessionId() + "'");
            }
            return ResponseEntity.ok(Map.of("session_id", c.sessionId(), "torn_down", true));
        }
        if (c.action() != null && !"create".equalsIgnoreCase(c.action())) {
            throw new IllegalArgumentException("action must be 'create' or 'teardown'");
        }
        if (c.ttlSeconds() != null && c.ttlSeconds() < 0) {
            throw new IllegalArgumentException("ttl_seconds must be >= 0 (0 = never expires)");
        }
        String id = c.sessionId() == null ? "sess-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong())
                : c.sessionId();
        SessionState s = sessions.create(id, c.ttlSeconds() == null ? null : Duration.ofSeconds(c.ttlSeconds()), c.labels());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionView.of(s));
    }

    @GetMapping
    public List<SessionView> list() {
        return sessions.list().stream().map(SessionView::of).toList();
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable("id") String id) {
        return SessionView.of(find(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> teardown(@PathVariable("id") String id) {
        if (!sessions.delete(id)) {
            throw new NotFoundException("no session '" + id + "'");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/state")
    public StateView state(@PathVariable("id") String id) {
        SessionState s = find(id);
        return new StateView(s.id(), s.entryCount(), s.snapshot());
    }

    /** Clears the session's state but keeps the session (UI "clear cache"). */
    @DeleteMapping("/{id}/state")
    public ResponseEntity<Void> clearState(@PathVariable("id") String id) {
        find(id).clear();
        return ResponseEntity.noContent().build();
    }

    private SessionState find(String id) {
        return sessions.get(id).orElseThrow(() -> new NotFoundException("no session '" + id + "'"));
    }
}
