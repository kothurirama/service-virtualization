package com.svp.engine.template;

import com.svp.engine.json.JsonValues;
import com.svp.engine.match.JsonPathExpr;
import com.svp.engine.match.PathPattern;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Compiles templates and {@code {{expression}}} placeholders. All validation (unknown roots,
 * unknown path variables, bad JSONPath, response references where no response exists yet) happens
 * here, at registration time, so the request path never fails on a malformed stub.
 *
 * <h2>Expressions</h2>
 * <pre>
 *   request.body[.path]            request.path            request.path.{var}  (alias: path.{var})
 *   request.headers.{name}         request.query.{name}    request.method      request.session_id
 *   response.body[.path]           response.status_code    (state_action and response headers only)
 *   state.value[.path]             state.values            state.count         state.key
 *   generate.id:PREFIX             generate.uuid           now                 now.epoch_millis
 *   'literal'  123  true  null     a ?: b                  (first present value)
 * </pre>
 */
public final class TemplateCompiler {

    public static final String MERGE_KEY = "$merge";

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{0,16}");

    private final PathPattern path;
    private final boolean allowResponseRefs;

    private TemplateCompiler(PathPattern path, boolean allowResponseRefs) {
        this.path = path;
        this.allowResponseRefs = allowResponseRefs;
    }

    /**
     * @param path              the stub's path pattern (validates {@code request.path.var} references)
     * @param allowResponseRefs whether {@code response.*} may be referenced (true once a response exists)
     */
    public static Template compile(Object template, PathPattern path, boolean allowResponseRefs) {
        return new TemplateCompiler(path, allowResponseRefs).node(template);
    }

    public static Expr compileExpression(String expression, PathPattern path, boolean allowResponseRefs) {
        return new TemplateCompiler(path, allowResponseRefs).expression(expression);
    }

    private Template node(Object template) {
        if (template instanceof String s) {
            return string(s);
        }
        if (template instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>(map.size());
            List<Template> values = new ArrayList<>(map.size());
            Expr merge = null;
            boolean constant = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.equals(MERGE_KEY)) {
                    if (!(e.getValue() instanceof String ms) || !(string(ms) instanceof Template.Single single)) {
                        throw new TemplateException("\"$merge\" must be a single {{expression}}");
                    }
                    merge = single.expr();
                    constant = false;
                    continue;
                }
                Template t = node(e.getValue());
                constant &= t.isConstant();
                keys.add(key);
                values.add(t);
            }
            if (constant) {
                return new Template.Constant(JsonValues.freeze(template));
            }
            return new Template.ObjectNode(keys.toArray(new String[0]), values.toArray(new Template[0]), merge);
        }
        if (template instanceof List<?> list) {
            List<Template> items = new ArrayList<>(list.size());
            boolean constant = true;
            for (Object item : list) {
                Template t = node(item);
                constant &= t.isConstant();
                items.add(t);
            }
            if (constant) {
                return new Template.Constant(JsonValues.freeze(template));
            }
            return new Template.ArrayNode(items.toArray(new Template[0]));
        }
        return new Template.Constant(template); // number, boolean, null
    }

    private Template string(String s) {
        int open = s.indexOf("{{");
        if (open < 0) {
            return new Template.Constant(s);
        }
        List<Object> parts = new ArrayList<>();
        int pos = 0;
        while (open >= 0) {
            int close = s.indexOf("}}", open + 2);
            if (close < 0) {
                throw new TemplateException("unclosed '{{' in template: " + s);
            }
            if (open > pos) {
                parts.add(s.substring(pos, open));
            }
            parts.add(expression(s.substring(open + 2, close)));
            pos = close + 2;
            open = s.indexOf("{{", pos);
        }
        if (pos < s.length()) {
            parts.add(s.substring(pos));
        }
        if (parts.size() == 1 && parts.get(0) instanceof Expr only) {
            return new Template.Single(only);
        }
        return new Template.Interpolated(parts.toArray());
    }

    Expr expression(String source) {
        String src = source.trim();
        if (src.isEmpty()) {
            throw new TemplateException("empty {{}} expression");
        }
        List<String> alternatives = splitAlternatives(src);
        if (alternatives.size() == 1) {
            return primary(alternatives.get(0));
        }
        Expr[] exprs = new Expr[alternatives.size()];
        for (int i = 0; i < exprs.length; i++) {
            exprs[i] = primary(alternatives.get(i));
        }
        return Expr.firstPresent(exprs);
    }

    private static List<String> splitAlternatives(String src) {
        List<String> out = new ArrayList<>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '?' && i + 1 < src.length() && src.charAt(i + 1) == ':') {
                out.add(src.substring(start, i).trim());
                start = i + 2;
                i++;
            }
        }
        out.add(src.substring(start).trim());
        for (String alt : out) {
            if (alt.isEmpty()) {
                throw new TemplateException("empty alternative in expression: " + src);
            }
        }
        return out;
    }

    private Expr primary(String src) {
        // literals
        if ((src.startsWith("'") && src.endsWith("'") && src.length() >= 2)
                || (src.startsWith("\"") && src.endsWith("\"") && src.length() >= 2)) {
            String literal = src.substring(1, src.length() - 1);
            return ctx -> literal;
        }
        if (NUMBER.matcher(src).matches()) {
            Object number = src.contains(".") ? (Object) new BigDecimal(src).doubleValue() : (Object) Long.parseLong(src);
            return ctx -> number;
        }
        switch (src) {
            case "true":
                return ctx -> Boolean.TRUE;
            case "false":
                return ctx -> Boolean.FALSE;
            case "null":
                return ctx -> null;
            default:
                break;
        }

        // generators
        if (src.equals("generate.id") || src.startsWith("generate.id:")) {
            String prefix = src.equals("generate.id") ? "ID" : src.substring("generate.id:".length()).trim();
            if (!PREFIX.matcher(prefix).matches()) {
                throw new TemplateException("generate.id prefix must be 0-16 letters/digits/underscore: " + prefix);
            }
            return ctx -> ctx.generateId(prefix);
        }
        if (src.equals("generate.uuid")) {
            return ctx -> UUID.randomUUID().toString();
        }
        if (src.equals("now") || src.equals("now.iso")) {
            return ctx -> ctx.now().toString();
        }
        if (src.equals("now.epoch_millis")) {
            return ctx -> ctx.now().toEpochMilli();
        }
        if (src.equals("now.date")) {
            return ctx -> ctx.now().toString().substring(0, 10);
        }

        // request
        if (src.equals("request.method")) {
            return ctx -> ctx.request().method();
        }
        if (src.equals("request.protocol")) {
            return ctx -> ctx.request().protocol();
        }
        if (src.equals("request.session_id") || src.equals("session.id")) {
            return ctx -> ctx.request().sessionId();
        }
        if (src.equals("request.path")) {
            return ctx -> ctx.request().path();
        }
        String pathVar = afterAny(src, "request.path.", "request.path_params.", "request.pathParams.", "path.");
        if (pathVar != null) {
            boolean tailRef = pathVar.equals("tail") && path != null && path.tailPosition() >= 0;
            if (path != null && !tailRef && !path.hasVariable(pathVar)) {
                throw new TemplateException("unknown path variable '" + pathVar + "' (path is " + path.source() + ")");
            }
            return ctx -> {
                String v = ctx.pathVariable(pathVar);
                return v == null ? JsonPathExpr.MISSING : v;
            };
        }
        String header = afterAny(src, "request.headers.", "request.header.");
        if (header != null) {
            return ctx -> {
                String v = ctx.request().header(header);
                return v == null ? JsonPathExpr.MISSING : v;
            };
        }
        String query = afterAny(src, "request.query.");
        if (query != null) {
            return ctx -> {
                String v = ctx.request().query().get(query);
                return v == null ? JsonPathExpr.MISSING : v;
            };
        }
        if (src.equals("request.body") || src.startsWith("request.body.") || src.startsWith("request.body[")) {
            JsonPathExpr jp = jsonPath(src, "request.body");
            return ctx -> jp.read(ctx.request().body());
        }

        // response (only after the response has been rendered)
        if (src.startsWith("response.")) {
            if (!allowResponseRefs) {
                throw new TemplateException("'" + src + "' is only available in state_action and response headers");
            }
            if (src.equals("response.status_code")) {
                return ctx -> ctx.responseStatus() == null ? JsonPathExpr.MISSING : ctx.responseStatus();
            }
            if (src.equals("response.body") || src.startsWith("response.body.") || src.startsWith("response.body[")) {
                JsonPathExpr jp = jsonPath(src, "response.body");
                return ctx -> ctx.responseBody() == JsonPathExpr.MISSING ? JsonPathExpr.MISSING : jp.read(ctx.responseBody());
            }
        }

        // state
        if (src.equals("state.values")) {
            return ctx -> ctx.stateValues() == null ? JsonPathExpr.MISSING : ctx.stateValues();
        }
        if (src.equals("state.count")) {
            return ctx -> ctx.stateValues() == null ? 0 : ctx.stateValues().size();
        }
        if (src.equals("state.key")) {
            return ctx -> ctx.stateKey() == null ? JsonPathExpr.MISSING : ctx.stateKey();
        }
        if (src.equals("state.value") || src.startsWith("state.value.") || src.startsWith("state.value[")) {
            JsonPathExpr jp = jsonPath(src, "state.value");
            return ctx -> ctx.stateValue() == JsonPathExpr.MISSING ? JsonPathExpr.MISSING : jp.read(ctx.stateValue());
        }

        throw new TemplateException("unknown template expression '" + src + "'");
    }

    private static JsonPathExpr jsonPath(String src, String root) {
        String rest = src.substring(root.length());
        try {
            return JsonPathExpr.compile("$" + rest);
        } catch (IllegalArgumentException e) {
            throw new TemplateException("invalid path in '" + src + "': " + e.getMessage());
        }
    }

    private static String afterAny(String src, String... prefixes) {
        for (String p : prefixes) {
            if (src.startsWith(p) && src.length() > p.length()) {
                return src.substring(p.length());
            }
        }
        return null;
    }
}
