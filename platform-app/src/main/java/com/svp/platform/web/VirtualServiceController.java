package com.svp.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.svp.engine.StubMatchingEngine;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data plane.
 * <ul>
 *   <li>{@code /virtual/**} — the virtual service itself: point the system under test here instead of
 *       the real dependency. Session comes from {@code X-Session-Id} (or {@code ?svp_session=}).</li>
 *   <li>{@code POST /api/v1/engine/execute} — the JSON request/response contract from the assignment,
 *       returning matched stub, matched rules and engine latency (used by scenario suites).</li>
 * </ul>
 * Injected drift delay is applied here, on the request's virtual thread, never inside the engine.
 */
@RestController
public class VirtualServiceController {

    public static final String PREFIX = "/virtual";
    public static final String SESSION_HEADER = "X-Session-Id";

    private final StubMatchingEngine engine;
    private final ObjectMapper mapper;

    public VirtualServiceController(StubMatchingEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    @PostMapping(path = "/api/v1/engine/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VirtualResponse execute(@RequestBody VirtualRequest request) throws InterruptedException {
        VirtualResponse response = engine.matchAndExecute(request);
        delay(response);
        return response;
    }

    @RequestMapping(PREFIX + "/**")
    public ResponseEntity<byte[]> virtual(HttpServletRequest http) throws IOException, InterruptedException {
        String uri = http.getRequestURI();
        String path = uri.substring(http.getContextPath().length() + PREFIX.length());
        if (path.isEmpty()) {
            path = "/";
        }
        String query = http.getQueryString();
        if (query != null) {
            path = path + "?" + query;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = http.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, http.getHeader(name));
        }
        String session = http.getHeader(SESSION_HEADER);
        if (session == null && query != null) {
            session = queryParam(query, "svp_session"); // never getParameter(): it would consume form bodies
        }
        VirtualResponse r = engine.matchAndExecute(new VirtualRequest(session, "HTTP", http.getMethod(), path, headers,
                readBody(http), null));
        delay(r);

        HttpHeaders out = new HttpHeaders();
        if (r.headers() != null) {
            r.headers().forEach(out::set);
        }
        if (r.matchedStubId() != null) {
            out.set("X-SVP-Matched-Stub", r.matchedStubId());
        }
        out.set("X-SVP-Session-Id", r.sessionId() == null ? "default" : r.sessionId());
        out.set("X-SVP-Engine-Latency-Ms", Double.toString(r.executionLatencyMs()));
        if (r.faultInjected()) {
            out.set("X-SVP-Fault-Injected", "true");
        }
        byte[] body = null;
        if (r.body() != null) {
            String contentType = out.getFirst(HttpHeaders.CONTENT_TYPE);
            body = r.body() instanceof String s && (contentType == null || !contentType.contains("json"))
                    ? s.getBytes(StandardCharsets.UTF_8)
                    : mapper.writeValueAsBytes(r.body());
        }
        return new ResponseEntity<>(body, out, HttpStatus.valueOf(r.statusCode()));
    }

    private Object readBody(HttpServletRequest http) throws IOException {
        byte[] bytes = http.getInputStream().readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        String contentType = http.getContentType();
        boolean json = contentType != null && contentType.contains("json");
        if (!json) {
            byte first = firstNonWhitespace(bytes);
            json = first == '{' || first == '[';
        }
        if (json) {
            try {
                return mapper.readValue(bytes, Object.class);
            } catch (IOException notJson) {
                // fall through: treat as text
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static byte firstNonWhitespace(byte[] bytes) {
        for (byte b : bytes) {
            if (!Character.isWhitespace(b)) {
                return b;
            }
        }
        return 0;
    }

    /** Simulated network delay; cheap because requests run on virtual threads. */
    private static void delay(VirtualResponse r) throws InterruptedException {
        if (r.injectedDelayMs() > 0) {
            Thread.sleep(r.injectedDelayMs());
        }
    }
}
