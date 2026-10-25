package com.svp.engine.model;

import java.util.Map;

/**
 * Protocol-neutral request handed to the engine. {@code path} may carry a query string;
 * {@code query} may also be given explicitly (both are merged). Header names are case-insensitive.
 */
public record VirtualRequest(
        String sessionId,
        String protocol,
        String method,
        String path,
        Map<String, String> headers,
        Object body,
        Map<String, String> query) {

    public VirtualRequest(String sessionId, String protocol, String method, String path,
                          Map<String, String> headers, Object body) {
        this(sessionId, protocol, method, path, headers, body, null);
    }
}
