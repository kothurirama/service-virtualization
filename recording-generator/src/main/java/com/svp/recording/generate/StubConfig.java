package com.svp.recording.generate;

import com.svp.engine.model.StubDefinition;

import java.time.Instant;
import java.util.List;

/** Output of the recording pipeline (shape follows the assignment's "Output Stub Configuration Schema"). */
public record StubConfig(
        String stubConfigId,
        String generatedBy,
        Instant generatedAt,
        String serviceName,
        SanitizationSummary sanitizationSummary,
        List<StubDefinition> virtualStubs,
        List<ReviewItem> reviewQueue,
        List<FieldDetection> fieldDetections) {

    public StubConfig withStubConfigId(String id) {
        return new StubConfig(id, generatedBy, generatedAt, serviceName, sanitizationSummary, virtualStubs, reviewQueue,
                fieldDetections);
    }
}
