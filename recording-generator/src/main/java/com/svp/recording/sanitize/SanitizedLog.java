package com.svp.recording.sanitize;

import java.util.List;
import java.util.Map;

/** A recorded exchange after sanitisation, plus what was masked. Safe to persist and display. */
public record SanitizedLog(
        String protocol,
        String method,
        String path,
        Map<String, String> query,
        Map<String, String> requestHeaders,
        Object requestBody,
        Integer responseStatus,
        Map<String, String> responseHeaders,
        Object responseBody,
        List<Detection> detections,
        int fieldsInspected) {

    public boolean isSensitive(String field) {
        for (Detection d : detections) {
            if (d.field().equals(field)) {
                return true;
            }
        }
        return false;
    }
}
