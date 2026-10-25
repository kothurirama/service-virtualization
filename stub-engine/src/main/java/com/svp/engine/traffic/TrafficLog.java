package com.svp.engine.traffic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free ring buffer of recent exchanges for the traffic viewer, plus cheap global counters.
 * Writers claim a slot with one atomic increment; old entries are overwritten. Readers copy a
 * consistent-enough snapshot (an entry is skipped if it was overwritten while reading).
 */
public final class TrafficLog {

    /** One handled request, as shown in the UI. */
    public record Entry(
            long seq,
            long timestampMillis,
            String sessionId,
            String method,
            String path,
            String matchedStubId,
            int statusCode,
            double engineLatencyMs,
            long injectedDelayMs,
            boolean faultInjected,
            boolean stateMutated,
            Object requestBody,
            Object responseBody) {
    }

    public record Summary(
            long totalRequests,
            long matched,
            long unmatched,
            long faultsInjected,
            long stateMutations,
            int sampleSize,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs) {
    }

    private final AtomicReferenceArray<Entry> ring;
    private final int mask;
    private final AtomicLong sequence = new AtomicLong();
    private final LongAdder total = new LongAdder();
    private final LongAdder matched = new LongAdder();
    private final LongAdder faults = new LongAdder();
    private final LongAdder mutations = new LongAdder();

    /** @param capacity rounded up to a power of two */
    public TrafficLog(int capacity) {
        int size = Integer.highestOneBit(Math.max(16, capacity - 1)) << 1;
        this.ring = new AtomicReferenceArray<>(size);
        this.mask = size - 1;
    }

    public long nextSeq() {
        return sequence.getAndIncrement();
    }

    public void record(Entry entry) {
        ring.set((int) (entry.seq() & mask), entry);
        total.increment();
        if (entry.matchedStubId() != null) {
            matched.increment();
        }
        if (entry.faultInjected()) {
            faults.increment();
        }
        if (entry.stateMutated()) {
            mutations.increment();
        }
    }

    /** Newest first, optionally filtered by session and/or stub, and only entries after {@code afterSeq}. */
    public List<Entry> recent(int limit, String sessionId, String stubId, long afterSeq) {
        int max = Math.max(0, Math.min(limit, ring.length()));
        List<Entry> out = new ArrayList<>(Math.min(max, 256));
        long next = sequence.get();
        long oldest = Math.max(0, next - ring.length());
        for (long s = next - 1; s >= oldest && out.size() < max; s--) {
            if (s <= afterSeq) {
                break;
            }
            Entry e = ring.get((int) (s & mask));
            if (e == null || e.seq() != s) {
                continue;
            }
            if (sessionId != null && !sessionId.equals(e.sessionId())) {
                continue;
            }
            if (stubId != null && !stubId.equals(e.matchedStubId())) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** Counters plus engine-latency percentiles over the entries currently in the ring. */
    public Summary summary() {
        List<Entry> window = recent(ring.length(), null, null, -1);
        double[] lat = new double[window.size()];
        for (int i = 0; i < lat.length; i++) {
            lat[i] = window.get(i).engineLatencyMs();
        }
        Arrays.sort(lat);
        long t = total.sum();
        long m = matched.sum();
        return new Summary(t, m, t - m, faults.sum(), mutations.sum(), lat.length,
                percentile(lat, 50), percentile(lat, 95), percentile(lat, 99),
                lat.length == 0 ? 0 : lat[lat.length - 1]);
    }

    /** Nearest-rank percentile over a sorted array. */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    public void clear() {
        for (int i = 0; i < ring.length(); i++) {
            ring.set(i, null);
        }
    }

    public int capacity() {
        return ring.length();
    }
}
