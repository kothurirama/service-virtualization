package com.svp.engine.match;

import com.svp.engine.StubValidationException;
import com.svp.engine.model.RequestMatcherDef;
import com.svp.engine.model.ResponseTemplateDef;
import com.svp.engine.model.StateActionDef;
import com.svp.engine.model.StubDefinition;
import com.svp.engine.template.Template;
import com.svp.engine.template.TemplateCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Validates and compiles a {@link StubDefinition}. Collects every error so an author fixes a stub
 * in one round trip.
 */
public final class StubCompiler {

    public static final Set<String> SUPPORTED_PROTOCOLS = Set.of("HTTP");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "*");
    private static final Pattern STUB_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private StubCompiler() {
    }

    public static CompiledStub compile(StubDefinition def, long sequence, CompiledStub.Stats stats) {
        List<String> errors = new ArrayList<>();
        String id = def == null ? null : def.stubId();
        if (def == null) {
            throw new StubValidationException("<null>", List.of("stub definition is required"));
        }
        if (id == null || !STUB_ID.matcher(id).matches()) {
            errors.add("stub_id is required and must match " + STUB_ID.pattern());
        }

        RequestMatcherDef rm = def.requestMatcher();
        String protocol = "HTTP";
        String method = null;
        PathPattern path = null;
        List<CompiledStub.HeaderConstraint> headers = new ArrayList<>();
        List<CompiledStub.QueryConstraint> query = new ArrayList<>();
        List<CompiledStub.BodyConstraint> body = new ArrayList<>();
        if (rm == null) {
            errors.add("request_matcher is required");
        } else {
            protocol = rm.protocol() == null ? "HTTP" : rm.protocol().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_PROTOCOLS.contains(protocol)) {
                errors.add("protocol '" + rm.protocol() + "' is not supported in this build (supported: HTTP; "
                        + "gRPC/Kafka are listed in BLOCKERS.md)");
            }
            method = rm.method() == null ? null : rm.method().toUpperCase(Locale.ROOT);
            if ("ANY".equals(method)) {
                method = "*";
            }
            if (method == null || !METHODS.contains(method)) {
                errors.add("request_matcher.method must be one of " + METHODS);
            }
            path = capture(errors, "request_matcher.path", () -> PathPattern.parse(rm.path()));
            if (rm.headers() != null) {
                for (Map.Entry<String, Object> e : rm.headers().entrySet()) {
                    boolean mediaType = e.getKey().equalsIgnoreCase("content-type") || e.getKey().equalsIgnoreCase("accept");
                    ValueMatcher m = capture(errors, "header '" + e.getKey() + "'",
                            () -> ValueMatcher.compile(e.getValue(), true, mediaType));
                    if (m != null) {
                        headers.add(new CompiledStub.HeaderConstraint(e.getKey(), m));
                    }
                }
            }
            if (rm.query() != null) {
                for (Map.Entry<String, Object> e : rm.query().entrySet()) {
                    ValueMatcher m = capture(errors, "query '" + e.getKey() + "'",
                            () -> ValueMatcher.compile(e.getValue(), true, false));
                    if (m != null) {
                        query.add(new CompiledStub.QueryConstraint(e.getKey(), m));
                    }
                }
            }
            if (rm.bodyJsonpath() != null) {
                for (Map.Entry<String, Object> e : rm.bodyJsonpath().entrySet()) {
                    JsonPathExpr jp = capture(errors, "body_jsonpath '" + e.getKey() + "'",
                            () -> JsonPathExpr.compile(e.getKey()));
                    ValueMatcher m = capture(errors, "body_jsonpath '" + e.getKey() + "' value",
                            () -> ValueMatcher.compile(e.getValue(), false, false));
                    if (jp != null && m != null) {
                        body.add(new CompiledStub.BodyConstraint(jp, m));
                    }
                }
            }
        }

        // state action first: it decides the default status code
        CompiledStub.StatePlan state = CompiledStub.StatePlan.NONE;
        StateActionDef sa = def.stateAction();
        if (sa != null) {
            state = compileState(sa, path, errors);
        }

        CompiledStub.ResponsePlan response = null;
        ResponseTemplateDef rt = def.responseTemplate();
        if (rt == null) {
            if (state.op() == CompiledStub.StateOp.NONE) {
                errors.add("response_template is required");
            }
            response = new CompiledStub.ResponsePlan(defaultStatus(state.op()), new String[0], new Template[0], null);
        } else {
            response = compileResponse(rt, defaultStatus(state.op()), path, "response_template", errors);
        }

        CompiledStub.ResponsePlan notFound = def.notFoundResponse() == null
                ? null
                : compileResponse(def.notFoundResponse(), 404, path, "not_found_response", errors);

        if (!errors.isEmpty()) {
            throw new StubValidationException(id == null ? "<missing>" : id, errors);
        }
        return new CompiledStub(def, protocol, method, path, sequence,
                headers.toArray(new CompiledStub.HeaderConstraint[0]),
                query.toArray(new CompiledStub.QueryConstraint[0]),
                body.toArray(new CompiledStub.BodyConstraint[0]),
                response, notFound, state, stats == null ? new CompiledStub.Stats() : stats);
    }

    private static CompiledStub.StatePlan compileState(StateActionDef sa, PathPattern path, List<String> errors) {
        CompiledStub.StateOp op;
        try {
            op = CompiledStub.StateOp.valueOf(sa.op() == null ? "" : sa.op().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("state_action.op must be one of PUT, GET, MERGE, DELETE, LIST");
            return CompiledStub.StatePlan.NONE;
        }
        if (op == CompiledStub.StateOp.NONE) {
            return CompiledStub.StatePlan.NONE;
        }
        if (sa.collection() == null || !COLLECTION.matcher(sa.collection()).matches()) {
            errors.add("state_action.collection is required and must match " + COLLECTION.pattern());
        }
        Template key = null;
        if (op != CompiledStub.StateOp.LIST) {
            if (sa.key() == null || sa.key().isBlank()) {
                errors.add("state_action.key is required for " + op);
            } else {
                // PUT renders after the response, so it may reference response.body
                boolean afterResponse = op == CompiledStub.StateOp.PUT;
                key = capture(errors, "state_action.key", () -> TemplateCompiler.compile(sa.key(), path, afterResponse));
            }
        }
        Template value = null;
        if (sa.value() != null) {
            if (op != CompiledStub.StateOp.PUT && op != CompiledStub.StateOp.MERGE) {
                errors.add("state_action.value is only used by PUT and MERGE");
            } else {
                boolean afterResponse = op == CompiledStub.StateOp.PUT;
                value = capture(errors, "state_action.value", () -> TemplateCompiler.compile(sa.value(), path, afterResponse));
            }
        }
        return new CompiledStub.StatePlan(op, sa.collection(), key, value);
    }

    private static CompiledStub.ResponsePlan compileResponse(ResponseTemplateDef rt, int defaultStatus, PathPattern path,
                                                             String field, List<String> errors) {
        int status = rt.statusCode() == null ? defaultStatus : rt.statusCode();
        if (status < 100 || status > 599) {
            errors.add(field + ".status_code must be between 100 and 599");
        }
        List<String> names = new ArrayList<>();
        List<Template> values = new ArrayList<>();
        if (rt.headers() != null) {
            for (Map.Entry<String, String> e : rt.headers().entrySet()) {
                // headers are rendered after the body, so they may reference response.body
                Template t = capture(errors, field + ".headers." + e.getKey(),
                        () -> TemplateCompiler.compile(e.getValue(), path, true));
                if (t != null) {
                    names.add(e.getKey());
                    values.add(t);
                }
            }
        }
        Template body = rt.body() == null ? null
                : capture(errors, field + ".body", () -> TemplateCompiler.compile(rt.body(), path, false));
        return new CompiledStub.ResponsePlan(status, names.toArray(new String[0]), values.toArray(new Template[0]), body);
    }

    static int defaultStatus(CompiledStub.StateOp op) {
        return switch (op) {
            case PUT -> 201;
            case DELETE -> 204;
            default -> 200;
        };
    }

    private static <T> T capture(List<String> errors, String where, Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            errors.add(where + ": " + e.getMessage());
            return null;
        }
    }
}
