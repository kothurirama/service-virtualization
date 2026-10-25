package com.svp.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svp.engine.StubRegistry;
import com.svp.engine.match.StubCompiler;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.model.StubSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stub management: register/update (single or batch), search, toggle, validate, delete. */
@RestController
@RequestMapping("/api/v1/stubs")
public class StubController {

    /** A stub plus its runtime metadata. */
    public record StubView(StubDefinition stub, String route, long hitCount, Instant lastHitAt,
                           Instant createdAt, Instant updatedAt) {

        static StubView of(StubRegistry.StubRecord r) {
            long last = r.compiled().stats().lastHitMillis();
            return new StubView(r.definition(), r.compiled().routeKey(), r.compiled().stats().hits(),
                    last == 0 ? null : Instant.ofEpochMilli(last), r.createdAt(), r.updatedAt());
        }
    }

    public record EnabledToggle(Boolean enabled) {
    }

    private final StubRegistry registry;
    private final ObjectMapper mapper;

    public StubController(StubRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    /** Body: one stub object, or an array of stubs (all-or-nothing). Existing ids are updated. */
    @PostMapping
    public ResponseEntity<Object> register(@RequestBody JsonNode body) {
        if (body.isArray()) {
            List<StubDefinition> defs = new ArrayList<>();
            body.forEach(n -> defs.add(toDefinition(n)));
            List<StubView> views = registry.upsertAll(defs).stream().map(StubView::of).toList();
            return ResponseEntity.status(HttpStatus.CREATED).body(views);
        }
        StubDefinition def = toDefinition(body);
        boolean existed = def.stubId() != null && registry.get(def.stubId()).isPresent();
        StubView view = StubView.of(registry.upsert(def));
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    private StubDefinition toDefinition(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("each stub must be a JSON object");
        }
        return mapper.convertValue(node, StubDefinition.class);
    }

    /** Compiles a stub without registering it (used by the UI editor). */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody StubDefinition def) {
        var compiled = StubCompiler.compile(def, 0, null);
        return Map.of("valid", true, "route", compiled.routeKey(), "matched_rules", compiled.matchedRules());
    }

    @GetMapping
    public List<StubView> search(@RequestParam(name = "q", required = false) String q,
                                 @RequestParam(name = "enabled", required = false) Boolean enabled,
                                 @RequestParam(name = "source", required = false) StubSource source) {
        return registry.search(q, enabled, source).stream().map(StubView::of).toList();
    }

    @GetMapping("/{id}")
    public StubView get(@PathVariable("id") String id) {
        return registry.get(id).map(StubView::of).orElseThrow(() -> new NotFoundException("no stub '" + id + "'"));
    }

    @PutMapping("/{id}")
    public StubView replace(@PathVariable("id") String id, @RequestBody StubDefinition def) {
        if (def.stubId() != null && !def.stubId().equals(id)) {
            throw new IllegalArgumentException("stub_id in body (" + def.stubId() + ") does not match path (" + id + ")");
        }
        StubDefinition withId = def.stubId() == null ? def.withStubId(id) : def;
        return StubView.of(registry.upsert(withId));
    }

    @PatchMapping("/{id}/enabled")
    public StubView toggle(@PathVariable("id") String id, @RequestBody EnabledToggle toggle) {
        if (toggle.enabled() == null) {
            throw new IllegalArgumentException("body must be {\"enabled\": true|false}");
        }
        return registry.setEnabled(id, toggle.enabled()).map(StubView::of)
                .orElseThrow(() -> new NotFoundException("no stub '" + id + "'"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        if (!registry.delete(id)) {
            throw new NotFoundException("no stub '" + id + "'");
        }
        return ResponseEntity.noContent().build();
    }
}
