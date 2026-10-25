package com.svp.recording;

import com.svp.recording.sanitize.SanitizedLog;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Flattens raw and sanitised exchanges into the same field paths used by the labels. */
public final class LogFlattener {

    private LogFlattener() {
    }

    public static Map<String, Object> flatten(RawTrafficLog log) {
        Map<String, Object> out = new LinkedHashMap<>();
        String path = log.path();
        int q = path.indexOf('?');
        out.put("request.path", q < 0 ? path : path.substring(0, q));
        if (q >= 0) {
            for (String pair : path.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                out.put("request.query." + URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        if (log.request() != null) {
            headers(out, "request.headers", log.request().headers());
            leaves(out, "request.body", log.request().body());
        }
        if (log.response() != null) {
            headers(out, "response.headers", log.response().headers());
            leaves(out, "response.body", log.response().body());
        }
        return out;
    }

    public static Map<String, Object> flatten(SanitizedLog log) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request.path", log.path());
        log.query().forEach((k, v) -> out.put("request.query." + k, v));
        headers(out, "request.headers", log.requestHeaders());
        leaves(out, "request.body", log.requestBody());
        headers(out, "response.headers", log.responseHeaders());
        leaves(out, "response.body", log.responseBody());
        return out;
    }

    private static void headers(Map<String, Object> out, String prefix, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach((k, v) -> out.put(prefix + "." + k, v));
        }
    }

    private static void leaves(Map<String, Object> out, String path, Object value) {
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> leaves(out, path + "." + k, v));
        } else if (value instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                leaves(out, path + "[" + i + "]", l.get(i));
            }
        } else if (value != null) {
            out.put(path, value);
        }
    }
}
