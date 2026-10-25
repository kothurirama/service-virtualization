package com.svp.engine;

import com.svp.engine.drift.DriftPolicy;
import com.svp.engine.json.JsonValues;
import com.svp.engine.match.CompiledStub;
import com.svp.engine.match.JsonPathExpr;
import com.svp.engine.match.RequestView;
import com.svp.engine.match.StubIndex;
import com.svp.engine.model.NearMiss;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import com.svp.engine.state.SessionLimitException;
import com.svp.engine.state.SessionState;
import com.svp.engine.state.SessionStateStore;
import com.svp.engine.template.Template;
import com.svp.engine.template.TemplateContext;
import com.svp.engine.traffic.TrafficLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data plane: match a request to the most specific stub, apply its session-state action and
 * render its response. Thread-safe and lock-free on the read path; never throws for a bad request
 * (errors become 4xx/5xx responses).
 *
 * <pre>
 *   parse view ─► session (by id) ─► index.find (trie + constraints) ─► drift decision
 *        └─► state pre-op (GET/LIST/MERGE/DELETE) ─► render body ─► state post-op (PUT) ─► headers
 * </pre>
 */
public final class StubMatchingEngine {

    private static final List<String> NO_RULES = List.of();
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    private final StubRegistry registry;
    private final SessionStateStore sessions;
    private final DriftPolicy drift;
    private final TrafficLog traffic;

    public StubMatchingEngine(StubRegistry registry, SessionStateStore sessions, DriftPolicy drift, TrafficLog traffic) {
        this.registry = registry;
        this.sessions = sessions;
        this.drift = drift;
        this.traffic = traffic;
    }

    /** Engine with in-memory defaults (30 min session TTL, 10k sessions, 2k-entry traffic ring). */
    public static StubMatchingEngine inMemory() {
        return new StubMatchingEngine(new StubRegistry(), SessionStateStore.withDefaults(), new DriftPolicy(),
                new TrafficLog(2048));
    }

    public VirtualResponse matchAndExecute(VirtualRequest request) {
        final long start = System.nanoTime();
        RequestView req;
        try {
            req = RequestView.of(request);
        } catch (IllegalArgumentException e) {
            return error(start, request, null, 400, "INVALID_REQUEST", e.getMessage());
        }

        SessionState session;
        try {
            session = sessions.getOrCreate(req.sessionId());
        } catch (SessionLimitException e) {
            return error(start, request, req, 429, "SESSION_LIMIT", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(start, request, req, 400, "INVALID_SESSION_ID", e.getMessage());
        }
        long nowMillis = System.currentTimeMillis();
        session.touch(nowMillis);

        StubIndex index = registry.index();
        CompiledStub stub = index.find(req);
        if (stub == null) {
            return miss(start, req, index);
        }
        stub.stats().hit(nowMillis);

        DriftPolicy.Decision decision = drift.decide(stub.routeKey());
        if (decision.fault()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "INJECTED_FAULT");
            body.put("message", "Simulated downstream failure (drift profile for " + stub.routeKey() + ")");
            return finish(start, req, stub, decision.faultStatus(), JSON_HEADERS, body, false,
                    decision.delayMs(), true, null);
        }

        try {
            return execute(start, req, session, stub, decision.delayMs());
        } catch (SessionLimitException e) {
            return finish(start, req, stub, 429, JSON_HEADERS, errorBody("SESSION_LIMIT", e.getMessage()),
                    false, 0, false, null);
        } catch (RuntimeException e) {
            return finish(start, req, stub, 500, JSON_HEADERS,
                    errorBody("STUB_EXECUTION_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage()),
                    false, 0, false, null);
        }
    }

    private VirtualResponse execute(long start, RequestView req, SessionState session, CompiledStub stub, long delayMs) {
        CompiledStub.StatePlan plan = stub.state();
        TemplateContext ctx = new TemplateContext(req, stub.pathPattern(), session);
        boolean mutated = false;
        Object defaultBody = null;

        switch (plan.op()) {
            case GET -> {
                String key = renderKey(plan, ctx);
                Object value = session.get(plan.collection(), key);
                if (value == null) {
                    return notFound(start, req, stub, ctx, plan, key, delayMs);
                }
                ctx.stateValue(value);
                defaultBody = value;
            }
            case LIST -> {
                List<Object> values = session.values(plan.collection());
                ctx.stateValues(values);
                defaultBody = values;
            }
            case MERGE -> {
                String key = renderKey(plan, ctx);
                Object patch = plan.value() == null ? req.body() : plan.value().render(ctx);
                Object merged = session.merge(plan.collection(), key, patch);
                if (merged == null) {
                    return notFound(start, req, stub, ctx, plan, key, delayMs);
                }
                ctx.stateValue(merged);
                defaultBody = merged;
                mutated = true;
            }
            case DELETE -> {
                String key = renderKey(plan, ctx);
                Object removed = session.remove(plan.collection(), key);
                if (removed == null) {
                    return notFound(start, req, stub, ctx, plan, key, delayMs);
                }
                ctx.stateValue(removed);
                mutated = true;
            }
            default -> {
                // NONE and PUT: nothing to read before rendering
            }
        }

        CompiledStub.ResponsePlan response = stub.response();
        Object body = response.body() == null ? defaultBody : response.body().render(ctx);
        ctx.responseBody(body);
        ctx.responseStatus(response.status());

        if (plan.op() == CompiledStub.StateOp.PUT) {
            String key = renderKey(plan, ctx);
            Object value = plan.value() == null ? body : plan.value().render(ctx);
            session.put(plan.collection(), key, value);
            mutated = true;
        }

        Map<String, String> headers = renderHeaders(response, ctx, body);
        return finish(start, req, stub, response.status(), headers, body, mutated, delayMs, false, null);
    }

    private VirtualResponse notFound(long start, RequestView req, CompiledStub stub, TemplateContext ctx,
                                     CompiledStub.StatePlan plan, String key, long delayMs) {
        CompiledStub.ResponsePlan nf = stub.notFound();
        ctx.stateKey(key);
        if (nf == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "NOT_FOUND");
            body.put("collection", plan.collection());
            body.put("key", key);
            body.put("session_id", req.sessionId());
            return finish(start, req, stub, 404, JSON_HEADERS, body, false, delayMs, false, null);
        }
        Object body = nf.body() == null ? null : nf.body().render(ctx);
        ctx.responseBody(body);
        ctx.responseStatus(nf.status());
        return finish(start, req, stub, nf.status(), renderHeaders(nf, ctx, body), body, false, delayMs, false, null);
    }

    private static String renderKey(CompiledStub.StatePlan plan, TemplateContext ctx) {
        Object key = plan.key().render(ctx);
        if (key == null || key == JsonPathExpr.MISSING || JsonValues.asText(key).isEmpty()) {
            throw new IllegalStateException("state_action.key resolved to an empty value");
        }
        return JsonValues.asText(key);
    }

    private static Map<String, String> renderHeaders(CompiledStub.ResponsePlan plan, TemplateContext ctx, Object body) {
        String[] names = plan.headerNames();
        if (names.length == 0) {
            return body == null ? Map.of() : JSON_HEADERS;
        }
        Map<String, String> out = new LinkedHashMap<>(names.length * 2 + 2);
        Template[] values = plan.headerValues();
        boolean hasContentType = false;
        for (int i = 0; i < names.length; i++) {
            out.put(names[i], JsonValues.asText(values[i].render(ctx)));
            hasContentType |= names[i].equalsIgnoreCase("Content-Type");
        }
        if (!hasContentType && body != null) {
            out.put("Content-Type", "application/json");
        }
        return out;
    }

    private VirtualResponse miss(long start, RequestView req, StubIndex index) {
        List<NearMiss> nearMisses = index.nearMisses(req, 3);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NO_MATCHING_STUB");
        body.put("message", "No stub matched " + req.method() + " " + req.path());
        if (!nearMisses.isEmpty()) {
            body.put("near_misses", nearMisses);
        }
        return finish(start, req, null, 404, JSON_HEADERS, body, false, 0, false, nearMisses);
    }

    private VirtualResponse error(long start, VirtualRequest raw, RequestView req, int status, String code, String message) {
        double latency = elapsedMs(start);
        String session = req != null ? req.sessionId() : raw == null ? null : raw.sessionId();
        return new VirtualResponse(null, status, JSON_HEADERS, errorBody(code, message), false, latency,
                NO_RULES, session, 0, false, null);
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    private VirtualResponse finish(long start, RequestView req, CompiledStub stub, int status, Map<String, String> headers,
                                   Object body, boolean mutated, long delayMs, boolean fault, List<NearMiss> nearMisses) {
        double latency = elapsedMs(start);
        String stubId = stub == null ? null : stub.id();
        VirtualResponse response = new VirtualResponse(stubId, status, headers, body, mutated, latency,
                stub == null ? NO_RULES : stub.matchedRules(), req.sessionId(), delayMs, fault,
                nearMisses == null || nearMisses.isEmpty() ? null : nearMisses);
        traffic.record(new TrafficLog.Entry(traffic.nextSeq(), System.currentTimeMillis(), req.sessionId(),
                req.method(), req.path(), stubId, status, latency, delayMs, fault, mutated, req.body(), body));
        return response;
    }

    /** Microsecond-resolution milliseconds. */
    private static double elapsedMs(long start) {
        long nanos = System.nanoTime() - start;
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }

    public StubRegistry registry() {
        return registry;
    }

    public SessionStateStore sessions() {
        return sessions;
    }

    public DriftPolicy drift() {
        return drift;
    }

    public TrafficLog traffic() {
        return traffic;
    }
}
