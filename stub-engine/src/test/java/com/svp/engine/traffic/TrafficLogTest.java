package com.svp.engine.traffic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficLogTest {

    private static TrafficLog.Entry entry(TrafficLog log, String session, String stub, double latency) {
        return new TrafficLog.Entry(log.nextSeq(), 0, session, "GET", "/x", stub, 200, latency, 0, false, false, null, null);
    }

    @Test
    void ringKeepsTheNewestEntriesNewestFirst() {
        TrafficLog log = new TrafficLog(16);
        for (int i = 0; i < 100; i++) {
            log.record(entry(log, "s" + (i % 2), i % 3 == 0 ? null : "stub", i));
        }
        var recent = log.recent(1000, null, null, -1);
        assertThat(recent).hasSize(log.capacity());
        assertThat(recent.get(0).seq()).isEqualTo(99);
        assertThat(log.recent(5, "s1", null, -1)).allMatch(e -> e.sessionId().equals("s1")).hasSize(5);
        assertThat(log.recent(1000, null, null, 95)).extracting(TrafficLog.Entry::seq).containsExactly(99L, 98L, 97L, 96L);
        TrafficLog.Summary summary = log.summary();
        assertThat(summary.totalRequests()).isEqualTo(100);
        assertThat(summary.unmatched()).isEqualTo(34);
        assertThat(summary.maxMs()).isEqualTo(99.0);
    }

    @Test
    void nearestRankPercentile() {
        double[] sorted = new double[100];
        for (int i = 0; i < 100; i++) {
            sorted[i] = i + 1;
        }
        assertThat(TrafficLog.percentile(sorted, 50)).isEqualTo(50);
        assertThat(TrafficLog.percentile(sorted, 99)).isEqualTo(99);
        assertThat(TrafficLog.percentile(sorted, 100)).isEqualTo(100);
        assertThat(TrafficLog.percentile(new double[0], 99)).isZero();
    }
}
