package com.svp.recording.generate;

import com.svp.recording.sanitize.Detection;

/** A masked field in a specific recorded exchange (original value is never kept). */
public record FieldDetection(
        int logIndex,
        String field,
        String type,
        String category,
        double confidence,
        String detector,
        String reason,
        String maskedAs) {

    static FieldDetection of(int logIndex, Detection d) {
        return new FieldDetection(logIndex, d.field(), d.type().name(), d.category().name(), d.confidence(),
                d.detector(), d.reason(), d.maskedAs());
    }
}
