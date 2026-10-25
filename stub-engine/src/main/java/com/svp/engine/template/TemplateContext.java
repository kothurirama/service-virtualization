package com.svp.engine.template;

import com.svp.engine.json.JsonValues;
import com.svp.engine.match.JsonPathExpr;
import com.svp.engine.match.PathPattern;
import com.svp.engine.match.RequestView;
import com.svp.engine.state.SessionState;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a template expression can read while one request is executed. Created per request;
 * not shared between threads.
 */
public final class TemplateContext {

    private final RequestView request;
    private final PathPattern pathPattern;
    private final SessionState session;
    private Instant now;

    private Object responseBody = JsonPathExpr.MISSING;
    private Integer responseStatus;
    private Object stateValue = JsonPathExpr.MISSING;
    private List<Object> stateValues;
    private String stateKey;
    private Map<String, String> generatedIds;

    public TemplateContext(RequestView request, PathPattern pathPattern, SessionState session) {
        this.request = request;
        this.pathPattern = pathPattern;
        this.session = session;
    }

    public RequestView request() {
        return request;
    }

    public SessionState session() {
        return session;
    }

    public Instant now() {
        if (now == null) {
            now = Instant.now();
        }
        return now;
    }

    /** Value of a named path variable, e.g. {@code /accounts/{id}} -> request.path.id */
    public String pathVariable(String name) {
        if (pathPattern == null) {
            return null;
        }
        if ("tail".equals(name) && pathPattern.tailPosition() >= 0) {
            return tail();
        }
        String[] names = pathPattern.variableNames();
        int[] positions = pathPattern.variablePositions();
        String[] segments = request.segments();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                int pos = positions[i];
                return pos < segments.length ? segments[pos] : null;
            }
        }
        return null;
    }

    private String tail() {
        String[] segments = request.segments();
        int from = pathPattern.tailPosition();
        if (from >= segments.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < segments.length; i++) {
            if (i > from) {
                sb.append('/');
            }
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    /** Ids generated in one request are memoised, so a body and a Location header agree. */
    public String generateId(String prefix) {
        if (generatedIds == null) {
            generatedIds = new HashMap<>(4);
        }
        return generatedIds.computeIfAbsent(prefix, session::nextId);
    }

    public Object responseBody() {
        return responseBody;
    }

    public void responseBody(Object body) {
        this.responseBody = body;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public void responseStatus(Integer status) {
        this.responseStatus = status;
    }

    public Object stateValue() {
        return stateValue;
    }

    public void stateValue(Object value) {
        this.stateValue = value;
    }

    public List<Object> stateValues() {
        return stateValues;
    }

    public void stateValues(List<Object> values) {
        this.stateValues = values;
    }

    public String stateKey() {
        return stateKey;
    }

    public void stateKey(String key) {
        this.stateKey = key;
    }

    static Object orNull(Object v) {
        return v == JsonPathExpr.MISSING ? null : v;
    }

    static String text(Object v) {
        return JsonValues.asText(orNull(v));
    }
}
