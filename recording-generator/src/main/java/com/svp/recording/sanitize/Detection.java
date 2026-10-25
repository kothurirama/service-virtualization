package com.svp.recording.sanitize;

/**
 * One masked field. Never carries the original value — only where it was, what it was judged to
 * be, how sure the detector is, and what replaced it.
 */
public record Detection(
        String field,
        SensitiveType type,
        Category category,
        double confidence,
        String detector,
        String reason,
        String maskedAs) {

    public boolean needsReview(double autoThreshold) {
        return confidence < autoThreshold;
    }
}
