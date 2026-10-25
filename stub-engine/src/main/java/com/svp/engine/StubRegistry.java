package com.svp.engine;

import com.svp.engine.match.CompiledStub;
import com.svp.engine.match.StubCompiler;
import com.svp.engine.match.StubIndex;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns stub definitions and publishes an immutable {@link StubIndex}.
 *
 * <p>Writes (rare: control plane) are serialised and rebuild the index; reads (every request) are a
 * single volatile load. Batch upserts are all-or-nothing: every stub is compiled before any is
 * published.
 */
public final class StubRegistry {

    /** Definition plus compiled form and metadata. */
    public record StubRecord(StubDefinition definition, CompiledStub compiled, Instant createdAt, Instant updatedAt) {
        public String id() {
            return definition.stubId();
        }
    }

    private final Map<String, StubRecord> stubs = new ConcurrentHashMap<>();
    private final Map<String, CompiledStub.Stats> stats = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object writeLock = new Object();
    private final Clock clock;
    private volatile StubIndex index = StubIndex.EMPTY;

    public StubRegistry() {
        this(Clock.systemUTC());
    }

    public StubRegistry(Clock clock) {
        this.clock = clock;
    }

    /** Hot path. */
    public StubIndex index() {
        return index;
    }

    public StubRecord upsert(StubDefinition definition) {
        return upsertAll(List.of(definition)).get(0);
    }

    /** All-or-nothing: if any definition is invalid, nothing is changed. */
    public List<StubRecord> upsertAll(List<StubDefinition> definitions) {
        synchronized (writeLock) {
            Map<String, StubDefinition> unique = new LinkedHashMap<>();
            for (StubDefinition d : definitions) {
                if (d != null && d.stubId() != null && unique.containsKey(d.stubId())) {
                    throw new StubValidationException(d.stubId(), List.of("duplicate stub_id in the same request"));
                }
                unique.put(d == null ? null : d.stubId(), d);
            }
            Instant now = clock.instant();
            List<StubRecord> compiled = new ArrayList<>(definitions.size());
            for (StubDefinition d : definitions) {
                CompiledStub.Stats s = d != null && d.stubId() != null
                        ? stats.getOrDefault(d.stubId(), new CompiledStub.Stats())
                        : new CompiledStub.Stats();
                CompiledStub c = StubCompiler.compile(d, sequence.incrementAndGet(), s);
                StubRecord existing = stubs.get(d.stubId());
                compiled.add(new StubRecord(d, c, existing == null ? now : existing.createdAt(), now));
            }
            for (StubRecord r : compiled) {
                stubs.put(r.id(), r);
                stats.put(r.id(), r.compiled().stats());
            }
            rebuild();
            return compiled;
        }
    }

    public boolean delete(String stubId) {
        synchronized (writeLock) {
            boolean removed = stubs.remove(stubId) != null;
            stats.remove(stubId);
            if (removed) {
                rebuild();
            }
            return removed;
        }
    }

    public Optional<StubRecord> setEnabled(String stubId, boolean enabled) {
        synchronized (writeLock) {
            StubRecord r = stubs.get(stubId);
            if (r == null) {
                return Optional.empty();
            }
            if (r.definition().enabled() == enabled) {
                return Optional.of(r);
            }
            return Optional.of(upsertAll(List.of(r.definition().withEnabled(enabled))).get(0));
        }
    }

    public void clear() {
        synchronized (writeLock) {
            stubs.clear();
            stats.clear();
            rebuild();
        }
    }

    private void rebuild() {
        List<CompiledStub> enabled = new ArrayList<>(stubs.size());
        for (StubRecord r : stubs.values()) {
            if (r.definition().enabled()) {
                enabled.add(r.compiled());
            }
        }
        index = StubIndex.build(enabled);
    }

    public Optional<StubRecord> get(String stubId) {
        return Optional.ofNullable(stubs.get(stubId));
    }

    /** Case-insensitive search over id, method, path, description and tags. */
    public List<StubRecord> search(String query, Boolean enabled, StubSource source) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<StubRecord> out = new ArrayList<>();
        for (StubRecord r : stubs.values()) {
            StubDefinition d = r.definition();
            if (enabled != null && d.enabled() != enabled) {
                continue;
            }
            if (source != null && d.source() != source) {
                continue;
            }
            if (!q.isEmpty() && !haystack(r).contains(q)) {
                continue;
            }
            out.add(r);
        }
        out.sort(Comparator.comparing(StubRecord::id));
        return out;
    }

    private static String haystack(StubRecord r) {
        StubDefinition d = r.definition();
        return (d.stubId() + " " + r.compiled().routeKey() + " " + (d.description() == null ? "" : d.description())
                + " " + String.join(" ", d.tags()) + " " + d.source()).toLowerCase(Locale.ROOT);
    }

    /** Distinct route keys ("POST /api/v1/payments") across all stubs, for per-route drift config. */
    public List<String> routeKeys() {
        TreeSet<String> keys = new TreeSet<>();
        for (StubRecord r : stubs.values()) {
            keys.add(r.compiled().routeKey());
        }
        return new ArrayList<>(keys);
    }

    public int size() {
        return stubs.size();
    }
}
