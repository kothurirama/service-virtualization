package com.svp.engine.state;

import com.svp.engine.json.JsonValues;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Private state of one session (one CI pipeline run). Nothing in here is reachable from another
 * session: the only way in is {@link SessionStateStore#getOrCreate(String)} keyed by session id.
 *
 * <p>Concurrency: collections are {@link ConcurrentHashMap}s, values are frozen (deep-immutable),
 * so reads are lock-free and never observe a half-written value. Read-modify-write operations
 * (merge, id generation) are atomic.
 */
public final class SessionState {

    /** First generated id is PREFIX-1001: readable, fixed width for a while, obviously synthetic. */
    static final long ID_START = 1000;

    private final String id;
    private final Instant createdAt;
    private final Duration ttl;
    private final boolean explicit;
    private final Map<String, String> labels;
    private final int maxEntries;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> collections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> idCounters = new ConcurrentHashMap<>();
    private final AtomicInteger entryCount = new AtomicInteger();
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder mutationCount = new LongAdder();
    private volatile long lastAccessMillis;

    SessionState(String id, Instant createdAt, Duration ttl, boolean explicit, Map<String, String> labels, int maxEntries) {
        this.id = id;
        this.createdAt = createdAt;
        this.ttl = ttl;
        this.explicit = explicit;
        this.labels = labels == null ? Map.of() : Map.copyOf(labels);
        this.maxEntries = maxEntries;
        this.lastAccessMillis = createdAt.toEpochMilli();
    }

    public void touch(long nowMillis) {
        lastAccessMillis = nowMillis;
        requestCount.increment();
    }

    public Object get(String collection, String key) {
        ConcurrentHashMap<String, Object> c = collections.get(collection);
        return c == null ? null : c.get(key);
    }

    /** Stores a frozen copy of {@code value}; returns the stored value. */
    public Object put(String collection, String key, Object value) {
        Object frozen = JsonValues.freeze(value);
        ConcurrentHashMap<String, Object> c = collections.computeIfAbsent(collection, k -> new ConcurrentHashMap<>());
        if (!c.containsKey(key)) {
            reserveEntry();
            if (c.putIfAbsent(key, frozen) != null) {
                entryCount.decrementAndGet(); // raced with another insert of the same key
                c.put(key, frozen);
            }
        } else {
            c.put(key, frozen);
        }
        mutationCount.increment();
        return frozen;
    }

    /**
     * Atomically applies an RFC 7386 merge patch to an existing value.
     * @return the merged (frozen) value, or null when the key does not exist
     */
    public Object merge(String collection, String key, Object patch) {
        ConcurrentHashMap<String, Object> c = collections.get(collection);
        if (c == null) {
            return null;
        }
        AtomicReference<Object> result = new AtomicReference<>();
        c.computeIfPresent(key, (k, existing) -> {
            Object merged = JsonValues.freeze(JsonValues.mergePatch(existing, patch));
            result.set(merged);
            return merged;
        });
        if (result.get() != null) {
            mutationCount.increment();
        }
        return result.get();
    }

    /** @return the removed value, or null when absent */
    public Object remove(String collection, String key) {
        ConcurrentHashMap<String, Object> c = collections.get(collection);
        if (c == null) {
            return null;
        }
        Object removed = c.remove(key);
        if (removed != null) {
            entryCount.decrementAndGet();
            mutationCount.increment();
        }
        return removed;
    }

    public List<Object> values(String collection) {
        ConcurrentHashMap<String, Object> c = collections.get(collection);
        if (c == null || c.isEmpty()) {
            return List.of();
        }
        // stable order for deterministic responses
        return List.copyOf(new TreeMap<>(c).values());
    }

    /** Deterministic per-session id: two sessions both get ACC-1001 and never influence each other. */
    public String nextId(String prefix) {
        long n = idCounters.computeIfAbsent(prefix, p -> new AtomicLong(ID_START)).incrementAndGet();
        return prefix.isEmpty() ? Long.toString(n) : prefix + "-" + n;
    }

    public void clear() {
        collections.clear();
        idCounters.clear();
        entryCount.set(0);
        mutationCount.increment();
    }

    private void reserveEntry() {
        if (entryCount.incrementAndGet() > maxEntries) {
            entryCount.decrementAndGet();
            throw new SessionLimitException("session '" + id + "' reached the limit of " + maxEntries + " state entries");
        }
    }

    /** Snapshot for inspection APIs: collection -> key -> value, keys sorted. */
    public Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        collections.forEach((name, c) -> out.put(name, Collections.unmodifiableMap(new TreeMap<>(c))));
        return Collections.unmodifiableMap(out);
    }

    public Map<String, Integer> collectionSizes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        new TreeMap<>(collections).forEach((name, c) -> out.put(name, c.size()));
        return out;
    }

    public List<String> collectionNames() {
        return new ArrayList<>(new TreeMap<>(collections).keySet());
    }

    public boolean isExpired(long nowMillis) {
        return !ttl.isZero() && nowMillis - lastAccessMillis > ttl.toMillis();
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessAt() {
        return Instant.ofEpochMilli(lastAccessMillis);
    }

    public Duration ttl() {
        return ttl;
    }

    public boolean explicit() {
        return explicit;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public int entryCount() {
        return entryCount.get();
    }

    public long requestCount() {
        return requestCount.sum();
    }

    public long mutationCount() {
        return mutationCount.sum();
    }
}
