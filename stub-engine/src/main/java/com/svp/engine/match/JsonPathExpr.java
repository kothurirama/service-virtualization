package com.svp.engine.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written JSONPath subset, compiled once at stub registration and evaluated without allocation
 * for single-valued paths.
 *
 * <p>Supported: {@code $}, {@code .name}, {@code ['name']}, {@code [n]} (negative = from end),
 * {@code [*]} / {@code .*} (any element), {@code ..name} (deep scan). Paths containing a wildcard
 * or deep scan are multi-valued: a constraint holds if <em>any</em> resolved value satisfies it.
 * Filters ({@code [?()]}) and slices are deliberately not supported — they are rarely needed for
 * request routing and are where general JSONPath engines spend their time.
 */
public final class JsonPathExpr {

    /** Sentinel for "path does not exist" (distinct from a present JSON null). */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "<missing>";
        }
    };

    private sealed interface Step permits Field, Index, Wildcard, DeepScan {
    }

    private record Field(String name) implements Step {
    }

    private record Index(int index) implements Step {
    }

    private record Wildcard() implements Step {
    }

    private record DeepScan(String name) implements Step {
    }

    private final String source;
    private final Step[] steps;
    private final boolean multiValued;

    private JsonPathExpr(String source, Step[] steps) {
        this.source = source;
        this.steps = steps;
        boolean multi = false;
        for (Step s : steps) {
            if (s instanceof Wildcard || s instanceof DeepScan) {
                multi = true;
                break;
            }
        }
        this.multiValued = multi;
    }

    public static JsonPathExpr compile(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("JSONPath expression is required");
        }
        String expr = expression.trim();
        if (!expr.startsWith("$")) {
            throw new IllegalArgumentException("JSONPath must start with '$': " + expression);
        }
        List<Step> steps = new ArrayList<>();
        int i = 1;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '.') {
                if (i + 1 < n && expr.charAt(i + 1) == '.') {
                    i += 2;
                    if (i < n && expr.charAt(i) == '[') {
                        int[] end = new int[1];
                        String name = readBracketName(expr, i, end, expression);
                        steps.add(new DeepScan(name));
                        i = end[0];
                    } else {
                        int start = i;
                        i = readIdentifierEnd(expr, i);
                        String name = expr.substring(start, i);
                        if (name.isEmpty() || name.equals("*")) {
                            throw new IllegalArgumentException("deep scan needs a field name: " + expression);
                        }
                        steps.add(new DeepScan(name));
                    }
                } else {
                    i++;
                    if (i < n && expr.charAt(i) == '*') {
                        steps.add(new Wildcard());
                        i++;
                    } else {
                        int start = i;
                        i = readIdentifierEnd(expr, i);
                        String name = expr.substring(start, i);
                        if (name.isEmpty()) {
                            throw new IllegalArgumentException("empty field name in JSONPath: " + expression);
                        }
                        steps.add(new Field(name));
                    }
                }
            } else if (c == '[') {
                if (i + 2 < n && expr.charAt(i + 1) == '*' && expr.charAt(i + 2) == ']') {
                    steps.add(new Wildcard());
                    i += 3;
                } else if (i + 1 < n && (expr.charAt(i + 1) == '\'' || expr.charAt(i + 1) == '"')) {
                    int[] end = new int[1];
                    steps.add(new Field(readBracketName(expr, i, end, expression)));
                    i = end[0];
                } else {
                    int close = expr.indexOf(']', i);
                    if (close < 0) {
                        throw new IllegalArgumentException("unclosed '[' in JSONPath: " + expression);
                    }
                    String idx = expr.substring(i + 1, close).trim();
                    try {
                        steps.add(new Index(Integer.parseInt(idx)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("unsupported JSONPath selector '[" + idx
                                + "]' (supported: [n], [*], ['name']): " + expression);
                    }
                    i = close + 1;
                }
            } else {
                throw new IllegalArgumentException("unexpected character '" + c + "' at " + i + " in JSONPath: "
                        + expression);
            }
        }
        return new JsonPathExpr(expr, steps.toArray(new Step[0]));
    }

    private static int readIdentifierEnd(String expr, int i) {
        int n = expr.length();
        while (i < n) {
            char ch = expr.charAt(i);
            if (ch == '.' || ch == '[' || ch == ']' || Character.isWhitespace(ch)) {
                break;
            }
            i++;
        }
        return i;
    }

    private static String readBracketName(String expr, int open, int[] end, String original) {
        char quote = expr.charAt(open + 1);
        int close = expr.indexOf(quote, open + 2);
        if (close < 0 || close + 1 >= expr.length() || expr.charAt(close + 1) != ']') {
            throw new IllegalArgumentException("unclosed quoted name in JSONPath: " + original);
        }
        end[0] = close + 2;
        return expr.substring(open + 2, close);
    }

    public String source() {
        return source;
    }

    public boolean isMultiValued() {
        return multiValued;
    }

    /** Single-valued read. Returns {@link #MISSING} when the path does not exist. */
    public Object read(Object root) {
        if (multiValued) {
            List<Object> all = new ArrayList<>(4);
            readAll(root, all);
            return all.isEmpty() ? MISSING : all.get(0);
        }
        Object cur = root;
        for (Step step : steps) {
            if (step instanceof Field f) {
                if (!(cur instanceof Map<?, ?> map) || !map.containsKey(f.name())) {
                    return MISSING;
                }
                cur = map.get(f.name());
            } else {
                Index ix = (Index) step;
                if (!(cur instanceof List<?> list)) {
                    return MISSING;
                }
                int idx = ix.index() < 0 ? list.size() + ix.index() : ix.index();
                if (idx < 0 || idx >= list.size()) {
                    return MISSING;
                }
                cur = list.get(idx);
            }
        }
        return cur;
    }

    /** Collects every value the path resolves to (wildcards and deep scans fan out). */
    public void readAll(Object root, List<Object> out) {
        collect(root, 0, out);
    }

    private void collect(Object cur, int stepIndex, List<Object> out) {
        if (stepIndex == steps.length) {
            out.add(cur);
            return;
        }
        Step step = steps[stepIndex];
        if (step instanceof Field f) {
            if (cur instanceof Map<?, ?> map && map.containsKey(f.name())) {
                collect(map.get(f.name()), stepIndex + 1, out);
            }
        } else if (step instanceof Index ix) {
            if (cur instanceof List<?> list) {
                int idx = ix.index() < 0 ? list.size() + ix.index() : ix.index();
                if (idx >= 0 && idx < list.size()) {
                    collect(list.get(idx), stepIndex + 1, out);
                }
            }
        } else if (step instanceof Wildcard) {
            if (cur instanceof List<?> list) {
                for (Object item : list) {
                    collect(item, stepIndex + 1, out);
                }
            } else if (cur instanceof Map<?, ?> map) {
                for (Object v : map.values()) {
                    collect(v, stepIndex + 1, out);
                }
            }
        } else if (step instanceof DeepScan ds) {
            deepScan(cur, ds.name(), stepIndex, out);
        }
    }

    private void deepScan(Object cur, String name, int stepIndex, List<Object> out) {
        if (cur instanceof Map<?, ?> map) {
            if (map.containsKey(name)) {
                collect(map.get(name), stepIndex + 1, out);
            }
            for (Object v : map.values()) {
                deepScan(v, name, stepIndex, out);
            }
        } else if (cur instanceof List<?> list) {
            for (Object item : list) {
                deepScan(item, name, stepIndex, out);
            }
        }
    }

    @Override
    public String toString() {
        return source;
    }
}
