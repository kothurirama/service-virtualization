package com.svp.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.svp.engine.json.Json;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** Loads the default stub catalogue shipped with the engine ({@code seed/seed-stubs.json}). */
public final class SeedStubs {

    public static final String DEFAULT_LOCATION = "seed/seed-stubs.json";

    private SeedStubs() {
    }

    public static List<StubDefinition> load() {
        return load(DEFAULT_LOCATION);
    }

    public static List<StubDefinition> load(String classpathLocation) {
        InputStream in = SeedStubs.class.getClassLoader().getResourceAsStream(classpathLocation);
        if (in == null) {
            throw new UncheckedIOException(new java.io.FileNotFoundException(classpathLocation));
        }
        return read(in);
    }

    public static List<StubDefinition> read(InputStream in) {
        List<StubDefinition> stubs = Json.read(in, new TypeReference<List<StubDefinition>>() { });
        return stubs.stream()
                .map(s -> s.source() == StubSource.MANUAL ? s.withSource(StubSource.SEED) : s)
                .toList();
    }
}
