package com.svp.recording;

import java.util.Map;

/**
 * One captured exchange, as POSTed to {@code /api/v1/recording/ingest}.
 * <pre>
 * {"method":"POST","path":"/api/v1/payments?x=1",
 *  "request":{"headers":{...},"body":{...}},
 *  "response":{"status_code":200,"headers":{...},"body":{...}}}
 * </pre>
 */
public record RawTrafficLog(
        String protocol,
        String method,
        String path,
        RawMessage request,
        RawMessage response,
        String recordedAt) {

    /** Shorthand used by the assignment's example: a POST whose captured response body is {@code body}. */
    public RawTrafficLog(String path, Map<String, Object> body) {
        this("HTTP", "POST", path, null, new RawMessage(200, Map.of("Content-Type", "application/json"), body), null);
    }
}
