package com.svp.recording.generate;

/**
 * Something a human must look at before the generated stubs go live: a low-confidence detection,
 * a stub that failed to compile, or conflicting recordings for the same request.
 */
public record ReviewItem(
        String fieldName,
        Integer logIndex,
        String stubId,
        String detectedType,
        Double confidence,
        String reason,
        String suggestedAction) {
}
