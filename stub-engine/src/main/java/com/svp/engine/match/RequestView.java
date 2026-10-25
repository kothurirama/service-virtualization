package com.svp.engine.match;

import com.svp.engine.model.VirtualRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalised, read-only view of an incoming request, built once per request.
 * Designed for the hot path: path split without regex, no header map copy (case-insensitive scan
 * over a handful of headers is cheaper than building a lower-cased map), query parsed only if present.
 */
public final class RequestView {

    public static final String DEFAULT_SESSION = "default";

    private static final String[] NO_SEGMENTS = new String[0];

    private final String sessionId;
    private final String protocol;
    private final String method;
    private final String path;
    private final String[] segments;
    private final Map<String, String> headers;
    private final Map<String, String> query;
    private final Object body;

    private RequestView(String sessionId, String protocol, String method, String path, String[] segments,
                        Map<String, String> headers, Map<String, String> query, Object body) {
        this.sessionId = sessionId;
        this.protocol = protocol;
        this.method = method;
        this.path = path;
        this.segments = segments;
        this.headers = headers;
        this.query = query;
        this.body = body;
    }

    public static RequestView of(VirtualRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.method() == null || request.method().isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        if (request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        String session = request.sessionId() == null || request.sessionId().isBlank()
                ? DEFAULT_SESSION : request.sessionId();
        String protocol = request.protocol() == null || request.protocol().isBlank()
                ? "HTTP" : request.protocol().toUpperCase(Locale.ROOT);
        String method = request.method().toUpperCase(Locale.ROOT);

        String rawPath = request.path();
        Map<String, String> query = request.query() == null ? Map.of() : request.query();
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            Map<String, String> merged = new LinkedHashMap<>(parseQuery(rawPath.substring(q + 1)));
            merged.putAll(query); // explicit query map wins
            query = merged;
            rawPath = rawPath.substring(0, q);
        }
        String[] segments = split(rawPath);
        Map<String, String> headers = request.headers() == null ? Map.of() : request.headers();
        return new RequestView(session, protocol, method, normalisedPath(segments), segments,
                headers, query, request.body());
    }

    /** Splits a path into non-empty segments ("/a//b/" -> [a, b]) without regex. */
    public static String[] split(String path) {
        if (path == null || path.isEmpty()) {
            return NO_SEGMENTS;
        }
        List<String> out = null;
        int len = path.length();
        int start = 0;
        for (int i = 0; i <= len; i++) {
            if (i == len || path.charAt(i) == '/') {
                if (i > start) {
                    if (out == null) {
                        out = new ArrayList<>(8);
                    }
                    out.add(path.substring(start, i));
                }
                start = i + 1;
            }
        }
        return out == null ? NO_SEGMENTS : out.toArray(NO_SEGMENTS);
    }

    static String normalisedPath(String[] segments) {
        if (segments.length == 0) {
            return "/";
        }
        StringBuilder sb = new StringBuilder(64);
        for (String s : segments) {
            sb.append('/').append(s);
        }
        return sb.toString();
    }

    static Map<String, String> parseQuery(String queryString) {
        if (queryString.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.putIfAbsent(decode(key), decode(value));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** Case-insensitive header lookup. */
    public String header(String name) {
        String exact = headers.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public String sessionId() {
        return sessionId;
    }

    public String protocol() {
        return protocol;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String[] segments() {
        return segments;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, String> query() {
        return query;
    }

    public Object body() {
        return body;
    }
}
