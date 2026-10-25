package com.svp.engine.model;

import java.util.Map;

/**
 * Declarative request matcher.
 *
 * <ul>
 *   <li>{@code path}: literal segments, {@code {var}} path variables, {@code *} (one segment),
 *       {@code **} (rest of path, last segment only).</li>
 *   <li>{@code headers}, {@code query}, {@code body_jsonpath}: each value is either a literal
 *       (equality) or an operator object such as {@code {"matches": "^Bearer .+"}},
 *       {@code {"exists": false}}, {@code {"contains": "x"}}, {@code {"gt": 10000}}.
 *       Several operators in one object are AND-ed.</li>
 * </ul>
 */
public record RequestMatcherDef(
        String protocol,
        String method,
        String path,
        Map<String, Object> headers,
        Map<String, Object> query,
        Map<String, Object> bodyJsonpath) {
}
