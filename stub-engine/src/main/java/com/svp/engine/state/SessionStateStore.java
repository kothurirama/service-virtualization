package com.svp.engine.state;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Root of all dynamic state: {@code sessionId -> SessionState}. Isolation is structural — every
 * state operation goes through a session obtained by id, and there is no cross-session query.
 *
 * <p>Sessions are created explicitly ({@code POST /api/v1/sessions}) or implicitly on first use,
 * expire after an idle TTL, and are bounded in number so one runaway pipeline cannot exhaust the
 * shared JVM.
 */
public final class SessionStateStore {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration defaultTtl;
    private final int maxSessions;
    private final int maxEntriesPerSession;

    public SessionStateStore(Clock clock, Duration defaultTtl, int maxSessions, int maxEntriesPerSession) {
        this.clock = clock;
        this.defaultTtl = defaultTtl;
        this.maxSessions = maxSessions;
        this.maxEntriesPerSession = maxEntriesPerSession;
    }

    public static SessionStateStore withDefaults() {
        return new SessionStateStore(Clock.systemUTC(), Duration.ofMinutes(30), 10_000, 50_000);
    }

    /** Hot path: returns the session, creating it implicitly on first use. */
    public SessionState getOrCreate(String sessionId) {
        SessionState existing = sessions.get(sessionId);
        if (existing != null) {
            return existing;
        }
        validateId(sessionId);
        return sessions.computeIfAbsent(sessionId, id -> newSession(id, defaultTtl, false, Map.of()));
    }

    /** Explicit creation (control plane). Re-creating an existing id returns the existing session. */
    public SessionState create(String sessionId, Duration ttl, Map<String, String> labels) {
        validateId(sessionId);
        Duration effectiveTtl = ttl == null ? defaultTtl : ttl;
        return sessions.computeIfAbsent(sessionId, id -> newSession(id, effectiveTtl, true, labels));
    }

    private SessionState newSession(String id, Duration ttl, boolean explicit, Map<String, String> labels) {
        if (sessions.size() >= maxSessions) {
            throw new SessionLimitException("session limit of " + maxSessions + " reached; tear down finished sessions");
        }
        return new SessionState(id, clock.instant(), ttl, explicit, labels, maxEntriesPerSession);
    }

    private static void validateId(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("session id must match " + SESSION_ID.pattern());
        }
    }

    public Optional<SessionState> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Tears a session down: all of its state disappears in one atomic map removal. */
    public boolean delete(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    public List<SessionState> list() {
        List<SessionState> out = new ArrayList<>(sessions.values());
        out.sort(Comparator.comparing(SessionState::lastAccessAt).reversed());
        return out;
    }

    public Collection<SessionState> all() {
        return sessions.values();
    }

    public int size() {
        return sessions.size();
    }

    /** @return number of sessions evicted because they were idle longer than their TTL */
    public int evictExpired() {
        long now = clock.millis();
        int[] evicted = {0};
        sessions.forEach((id, s) -> {
            if (s.isExpired(now) && sessions.remove(id, s)) {
                evicted[0]++;
            }
        });
        return evicted[0];
    }

    public long nowMillis() {
        return clock.millis();
    }

    public Clock clock() {
        return clock;
    }
}
