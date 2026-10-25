package com.svp.recording.generate;

/** Counts are per field (a field is counted once even if several values inside it were masked). */
public record SanitizationSummary(
        int totalFieldsInspected,
        int pciFieldsMasked,
        int piiFieldsMasked,
        int secretFieldsMasked,
        boolean requiresHumanReview,
        int logsProcessed,
        int stubsGenerated,
        Double lowestConfidence) {
}
