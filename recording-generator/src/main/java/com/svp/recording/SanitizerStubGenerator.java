package com.svp.recording;

import com.svp.recording.generate.StubConfig;
import com.svp.recording.generate.StubGenerator;
import com.svp.recording.sanitize.Masker;
import com.svp.recording.sanitize.SanitizedLog;
import com.svp.recording.sanitize.SensitiveDataSanitizer;

import java.time.Clock;
import java.util.List;

/**
 * Entry point of the recording pipeline: raw traffic → sanitised traffic → virtual stubs.
 * Framework-free and thread-safe; the Spring app wraps it in a REST controller.
 */
public final class SanitizerStubGenerator {

    private final SensitiveDataSanitizer sanitizer;
    private final StubGenerator generator;

    public SanitizerStubGenerator() {
        this(new SensitiveDataSanitizer(new Masker()), Clock.systemUTC());
    }

    public SanitizerStubGenerator(SensitiveDataSanitizer sanitizer, Clock clock) {
        this.sanitizer = sanitizer;
        this.generator = new StubGenerator(sanitizer, clock);
    }

    /** One exchange → a stub config (the assignment's {@code generateFromLog}). */
    public StubConfig generateFromLog(RawTrafficLog log) {
        return generator.generate(null, List.of(log));
    }

    public StubConfig generate(String serviceName, List<RawTrafficLog> logs) {
        return generator.generate(serviceName, logs);
    }

    public SanitizedLog sanitize(RawTrafficLog log) {
        return sanitizer.sanitize(log);
    }

    public SensitiveDataSanitizer.Inspection inspect(Object json) {
        return sanitizer.inspect(json);
    }
}
