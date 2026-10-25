package com.svp.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svp.platform.service.RecordingService;
import com.svp.recording.RawTrafficLog;
import com.svp.recording.SanitizerStubGenerator;
import com.svp.recording.sanitize.SanitizedLog;
import com.svp.recording.sanitize.SensitiveDataSanitizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording / sanitisation API.
 * <ul>
 *   <li>{@code POST /ingest} — raw traffic logs in, sanitised stub configuration out (published or held).</li>
 *   <li>{@code POST /sanitize} — sanitise any JSON document and list what was masked (no stubs created).</li>
 *   <li>{@code POST /sanitize-log} — sanitise one recorded exchange.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/recording")
public class RecordingController {

    private final RecordingService recordings;
    private final SanitizerStubGenerator pipeline;
    private final ObjectMapper mapper;

    public RecordingController(RecordingService recordings, SanitizerStubGenerator pipeline, ObjectMapper mapper) {
        this.recordings = recordings;
        this.pipeline = pipeline;
        this.mapper = mapper;
    }

    /**
     * Body: either an array of logs, or {"service_name": "...", "auto_publish": true, "logs": [...]}.
     * auto_publish defaults to true; anything flagged for review is held regardless.
     */
    @PostMapping("/ingest")
    public ResponseEntity<RecordingService.RecordingView> ingest(@RequestBody JsonNode body) {
        String service = null;
        boolean autoPublish = true;
        JsonNode logsNode = body;
        if (body.isObject()) {
            service = body.hasNonNull("service_name") ? body.get("service_name").asText() : null;
            autoPublish = !body.has("auto_publish") || body.get("auto_publish").asBoolean(true);
            logsNode = body.has("logs") ? body.get("logs") : mapper.createArrayNode().add(body);
        }
        if (!logsNode.isArray()) {
            throw new IllegalArgumentException("logs must be an array of recorded exchanges");
        }
        List<RawTrafficLog> logs = new ArrayList<>();
        for (JsonNode n : logsNode) {
            RawTrafficLog log = mapper.convertValue(n, RawTrafficLog.class);
            if (log.path() == null || log.method() == null) {
                throw new IllegalArgumentException("every log needs at least 'method' and 'path'");
            }
            logs.add(log);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(recordings.ingest(service, logs, autoPublish));
    }

    @PostMapping("/sanitize")
    public SensitiveDataSanitizer.Inspection sanitize(@RequestBody Object json) {
        return pipeline.inspect(json);
    }

    @PostMapping("/sanitize-log")
    public SanitizedLog sanitizeLog(@RequestBody RawTrafficLog log) {
        return pipeline.sanitize(log);
    }

    @GetMapping("/configs")
    public List<RecordingService.RecordingView> list() {
        return recordings.list();
    }

    @GetMapping("/configs/{id}")
    public RecordingService.RecordingView get(@PathVariable("id") String id) {
        return recordings.find(id);
    }

    @PostMapping("/configs/{id}/approve")
    public RecordingService.RecordingView approve(@PathVariable("id") String id) {
        return recordings.approve(id);
    }

    @PostMapping("/configs/{id}/reject")
    public RecordingService.RecordingView reject(@PathVariable("id") String id) {
        return recordings.reject(id);
    }
}
