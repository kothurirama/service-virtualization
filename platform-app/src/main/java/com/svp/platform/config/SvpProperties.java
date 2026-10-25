package com.svp.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** All platform tunables ({@code svp.*} in application.yml). */
@ConfigurationProperties("svp")
public record SvpProperties(
        @DefaultValue Sessions sessions,
        @DefaultValue Traffic traffic,
        @DefaultValue Seed seed,
        @DefaultValue Warmup warmup,
        @DefaultValue Recording recording) {

    public record Sessions(
            @DefaultValue("30m") Duration defaultTtl,
            @DefaultValue("10000") int maxSessions,
            @DefaultValue("50000") int maxEntriesPerSession,
            @DefaultValue("30s") Duration sweepInterval) {
    }

    public record Traffic(@DefaultValue("2048") int capacity) {
    }

    public record Seed(@DefaultValue("true") boolean enabled,
                       @DefaultValue("seed/seed-stubs.json") String location) {
    }

    public record Warmup(@DefaultValue("10000") int requests) {
    }

    public record Recording(@DefaultValue("200") int maxStoredConfigs) {
    }
}
