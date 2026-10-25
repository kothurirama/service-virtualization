package com.svp.engine.match;

import com.svp.engine.json.JsonValues;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A compiled predicate over one value. {@code actual} is {@link JsonPathExpr#MISSING} (or null for
 * headers/query) when the value is absent.
 *
 * <p>Spec forms accepted in stub JSON:
 * <pre>
 *   "USD"                              equality (typed for bodies, textual for headers/query)
 *   {"equals": 500}                    explicit equality
 *   {"matches": "^Bearer \\S+$"}       full-match regex (precompiled)
 *   {"contains": "json"}               substring
 *   {"exists": false}                  presence check
 *   {"gt": 10000} / gte / lt / lte     numeric comparison
 *   {"gte": 100, "lt": 1000}           several operators are AND-ed
 * </pre>
 */
public interface ValueMatcher {

    boolean test(Object actual);

    String describe();

    /** Operators recognised inside an operator object. */
    List<String> OPERATORS = List.of("equals", "matches", "contains", "exists", "gt", "gte", "lt", "lte");

    /**
     * @param textual true for headers and query params (values are strings; compare textually)
     * @param mediaType true for Content-Type / Accept: equality ignores parameters such as charset
     */
    static ValueMatcher compile(Object spec, boolean textual, boolean mediaType) {
        if (spec instanceof Map<?, ?> map && !map.isEmpty() && OPERATORS.containsAll(keys(map))) {
            List<ValueMatcher> all = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                all.add(compileOperator(String.valueOf(e.getKey()), e.getValue(), textual, mediaType));
            }
            return all.size() == 1 ? all.get(0) : new AllOf(List.copyOf(all));
        }
        if (spec instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Object k : map.keySet()) {
                if (!OPERATORS.contains(String.valueOf(k)) && String.valueOf(k).length() < 12
                        && map.size() == 1 && isLikelyOperatorTypo(String.valueOf(k))) {
                    throw new IllegalArgumentException("unknown matcher operator '" + k + "'; supported: " + OPERATORS);
                }
            }
        }
        return equalsMatcher(spec, textual, mediaType);
    }

    private static boolean isLikelyOperatorTypo(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("regex") || k.equals("match") || k.equals("equal") || k.equals("exist")
                || k.equals("contain") || k.equals("eq") || k.equals("ne") || k.equals("not");
    }

    private static List<String> keys(Map<?, ?> map) {
        List<String> out = new ArrayList<>(map.size());
        for (Object k : map.keySet()) {
            out.add(String.valueOf(k));
        }
        return out;
    }

    private static ValueMatcher compileOperator(String op, Object arg, boolean textual, boolean mediaType) {
        return switch (op) {
            case "equals" -> equalsMatcher(arg, textual, mediaType);
            case "matches" -> {
                if (!(arg instanceof String regex)) {
                    throw new IllegalArgumentException("'matches' needs a regex string");
                }
                try {
                    yield new Matches(Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("invalid regex '" + regex + "': " + e.getDescription());
                }
            }
            case "contains" -> {
                if (arg == null) {
                    throw new IllegalArgumentException("'contains' needs a value");
                }
                yield new Contains(String.valueOf(arg));
            }
            case "exists" -> {
                if (!(arg instanceof Boolean b)) {
                    throw new IllegalArgumentException("'exists' needs true or false");
                }
                yield new Exists(b);
            }
            case "gt", "gte", "lt", "lte" -> {
                BigDecimal bound = JsonValues.asNumber(arg);
                if (bound == null) {
                    throw new IllegalArgumentException("'" + op + "' needs a number");
                }
                yield new Compare(op, bound);
            }
            default -> throw new IllegalArgumentException("unknown matcher operator '" + op + "'");
        };
    }

    private static ValueMatcher equalsMatcher(Object expected, boolean textual, boolean mediaType) {
        if (textual) {
            String text = expected == null ? null : JsonValues.asText(expected);
            return mediaType ? new MediaTypeEquals(text) : new TextEquals(text);
        }
        return new JsonEquals(JsonValues.freeze(expected));
    }

    static boolean absent(Object actual) {
        return actual == null || actual == JsonPathExpr.MISSING;
    }

    record JsonEquals(Object expected) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            if (actual == JsonPathExpr.MISSING) {
                return false;
            }
            return JsonValues.jsonEquals(expected, actual);
        }

        @Override
        public String describe() {
            return "equals " + JsonValues.asText(expected);
        }
    }

    record TextEquals(String expected) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            return !absent(actual) && actual.toString().equals(expected);
        }

        @Override
        public String describe() {
            return "equals '" + expected + "'";
        }
    }

    /** "application/json" equals "application/json; charset=UTF-8" (case-insensitive type). */
    record MediaTypeEquals(String expected) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            if (absent(actual) || expected == null) {
                return false;
            }
            String a = actual.toString();
            int semi = a.indexOf(';');
            String type = (semi < 0 ? a : a.substring(0, semi)).trim();
            int expSemi = expected.indexOf(';');
            if (expSemi >= 0) {
                return a.replace(" ", "").equalsIgnoreCase(expected.replace(" ", ""));
            }
            return type.equalsIgnoreCase(expected.trim());
        }

        @Override
        public String describe() {
            return "media type '" + expected + "'";
        }
    }

    record Matches(Pattern pattern) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            return !absent(actual) && !(actual instanceof Map) && !(actual instanceof List)
                    && pattern.matcher(JsonValues.asText(actual)).matches();
        }

        @Override
        public String describe() {
            return "matches /" + pattern.pattern() + "/";
        }
    }

    record Contains(String needle) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            if (absent(actual)) {
                return false;
            }
            if (actual instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && JsonValues.asText(item).equals(needle)) {
                        return true;
                    }
                }
                return false;
            }
            return JsonValues.asText(actual).contains(needle);
        }

        @Override
        public String describe() {
            return "contains '" + needle + "'";
        }
    }

    record Exists(boolean shouldExist) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            boolean present = actual != JsonPathExpr.MISSING && actual != null;
            return present == shouldExist;
        }

        @Override
        public String describe() {
            return shouldExist ? "is present" : "is absent";
        }
    }

    record Compare(String op, BigDecimal bound) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            BigDecimal value = absent(actual) ? null : JsonValues.asNumber(actual);
            if (value == null) {
                return false;
            }
            int c = value.compareTo(bound);
            return switch (op) {
                case "gt" -> c > 0;
                case "gte" -> c >= 0;
                case "lt" -> c < 0;
                default -> c <= 0;
            };
        }

        @Override
        public String describe() {
            return op + " " + bound.toPlainString();
        }
    }

    record AllOf(List<ValueMatcher> all) implements ValueMatcher {
        @Override
        public boolean test(Object actual) {
            for (ValueMatcher m : all) {
                if (!m.test(actual)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (ValueMatcher m : all) {
                if (!sb.isEmpty()) {
                    sb.append(" and ");
                }
                sb.append(m.describe());
            }
            return sb.toString();
        }
    }
}
