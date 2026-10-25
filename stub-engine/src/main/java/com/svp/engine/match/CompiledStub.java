package com.svp.engine.match;

import com.svp.engine.model.StubDefinition;
import com.svp.engine.template.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Immutable, pre-validated execution plan for one stub. Everything that can be computed ahead of
 * time is: regexes, JSONPaths, templates, the matched-rules list and the route key.
 */
public final class CompiledStub {

    public enum StateOp { NONE, PUT, GET, MERGE, DELETE, LIST }

    public record HeaderConstraint(String name, ValueMatcher matcher) {
    }

    public record QueryConstraint(String name, ValueMatcher matcher) {
    }

    public record BodyConstraint(JsonPathExpr path, ValueMatcher matcher) {
    }

    /** Status, headers and body template of a response. {@code body == null} means "use the default". */
    public record ResponsePlan(int status, String[] headerNames, Template[] headerValues, Template body) {
    }

    public record StatePlan(StateOp op, String collection, Template key, Template value) {
        public static final StatePlan NONE = new StatePlan(StateOp.NONE, null, null, null);
    }

    /** Per-stub runtime counters; survive recompilation of the index. */
    public static final class Stats {
        private final LongAdder hits = new LongAdder();
        private volatile long lastHitMillis;

        public void hit(long nowMillis) {
            hits.increment();
            lastHitMillis = nowMillis;
        }

        public long hits() {
            return hits.sum();
        }

        public long lastHitMillis() {
            return lastHitMillis;
        }
    }

    private final StubDefinition definition;
    private final String protocol;
    private final String method;
    private final PathPattern pathPattern;
    private final String routeKey;
    private final long sequence;
    private final HeaderConstraint[] headers;
    private final QueryConstraint[] query;
    private final BodyConstraint[] body;
    private final List<String> matchedRules;
    private final ResponsePlan response;
    private final ResponsePlan notFound;
    private final StatePlan state;
    private final Stats stats;

    CompiledStub(StubDefinition definition, String protocol, String method, PathPattern pathPattern, long sequence,
                 HeaderConstraint[] headers, QueryConstraint[] query, BodyConstraint[] body,
                 ResponsePlan response, ResponsePlan notFound, StatePlan state, Stats stats) {
        this.definition = definition;
        this.protocol = protocol;
        this.method = method;
        this.pathPattern = pathPattern;
        this.routeKey = method + " " + pathPattern.canonical();
        this.sequence = sequence;
        this.headers = headers;
        this.query = query;
        this.body = body;
        this.response = response;
        this.notFound = notFound;
        this.state = state;
        this.stats = stats;
        List<String> rules = new ArrayList<>(4);
        rules.add(pathPattern.isLiteral() ? "path_exact" : "path_pattern");
        if (headers.length > 0) {
            rules.add("header_constraints");
        }
        if (query.length > 0) {
            rules.add("query_constraints");
        }
        if (body.length > 0) {
            rules.add("jsonpath_body_check");
        }
        this.matchedRules = List.copyOf(rules);
    }

    /** Hot path: evaluates every non-path constraint. */
    public boolean matches(RequestView request) {
        for (HeaderConstraint h : headers) {
            if (!h.matcher().test(request.header(h.name()))) {
                return false;
            }
        }
        for (QueryConstraint q : query) {
            if (!q.matcher().test(request.query().get(q.name()))) {
                return false;
            }
        }
        if (body.length > 0) {
            Object root = request.body();
            for (BodyConstraint b : body) {
                if (!bodyMatches(b, root)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean bodyMatches(BodyConstraint b, Object root) {
        if (!b.path().isMultiValued()) {
            return b.matcher().test(b.path().read(root));
        }
        List<Object> values = new ArrayList<>(4);
        b.path().readAll(root, values);
        if (values.isEmpty()) {
            return b.matcher().test(JsonPathExpr.MISSING);
        }
        for (Object v : values) {
            if (b.matcher().test(v)) {
                return true;
            }
        }
        return false;
    }

    /** Diagnostic path (misses only): describes the first constraint that fails, or null. */
    public String[] firstFailure(RequestView request) {
        for (HeaderConstraint h : headers) {
            String actual = request.header(h.name());
            if (!h.matcher().test(actual)) {
                return new String[]{"header_constraints", "header '" + h.name() + "' " + h.matcher().describe()
                        + " (was " + (actual == null ? "absent" : "'" + actual + "'") + ")"};
            }
        }
        for (QueryConstraint q : query) {
            String actual = request.query().get(q.name());
            if (!q.matcher().test(actual)) {
                return new String[]{"query_constraints", "query '" + q.name() + "' " + q.matcher().describe()
                        + " (was " + (actual == null ? "absent" : "'" + actual + "'") + ")"};
            }
        }
        for (BodyConstraint b : body) {
            if (!bodyMatches(b, request.body())) {
                return new String[]{"jsonpath_body_check", b.path().source() + " " + b.matcher().describe()};
            }
        }
        return null;
    }

    public int constraintCount() {
        return headers.length + query.length + body.length;
    }

    public String id() {
        return definition.stubId();
    }

    public StubDefinition definition() {
        return definition;
    }

    public String protocol() {
        return protocol;
    }

    /** Upper-case HTTP method, or "*" for any method. */
    public String method() {
        return method;
    }

    public PathPattern pathPattern() {
        return pathPattern;
    }

    /** "POST /api/v1/payments" — the unit drift profiles are configured on. */
    public String routeKey() {
        return routeKey;
    }

    public int priority() {
        return definition.priority();
    }

    public long sequence() {
        return sequence;
    }

    public List<String> matchedRules() {
        return matchedRules;
    }

    public ResponsePlan response() {
        return response;
    }

    public ResponsePlan notFound() {
        return notFound;
    }

    public StatePlan state() {
        return state;
    }

    public Stats stats() {
        return stats;
    }
}
