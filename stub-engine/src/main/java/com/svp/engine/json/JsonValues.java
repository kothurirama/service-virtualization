package com.svp.engine.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helpers for the "plain JSON" value model used everywhere in the engine:
 * {@code Map<String,Object>}, {@code List<Object>}, String, Number, Boolean, null.
 */
public final class JsonValues {

    private JsonValues() {
    }

    /**
     * Deep-copies a JSON tree into unmodifiable collections. Values stored in session state are
     * frozen so one caller can never mutate data another caller (or session) later reads.
     */
    public static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(capacity(map.size()));
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), freeze(e.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Object[] array) {
            ArrayList<Object> copy = new ArrayList<>(array.length);
            for (Object item : array) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value; // String, Number, Boolean and null are immutable
    }

    /** Deep mutable copy (used when a frozen value must be edited, e.g. merge patch). */
    public static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(capacity(map.size()));
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), mutableCopy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(mutableCopy(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * RFC 7386 JSON Merge Patch: objects merge recursively, {@code null} removes a key,
     * anything else replaces.
     */
    @SuppressWarnings("unchecked")
    public static Object mergePatch(Object target, Object patch) {
        if (!(patch instanceof Map<?, ?> patchMap)) {
            return mutableCopy(patch);
        }
        LinkedHashMap<String, Object> result = target instanceof Map<?, ?> t
                ? (LinkedHashMap<String, Object>) mutableCopy(t)
                : new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : patchMap.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (e.getValue() == null) {
                result.remove(key);
            } else {
                result.put(key, mergePatch(result.get(key), e.getValue()));
            }
        }
        return result;
    }

    /** JSON equality with numeric leniency (500 equals 500.0). */
    public static boolean jsonEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return numericEquals(na, nb);
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !jsonEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            Iterator<?> ia = la.iterator();
            Iterator<?> ib = lb.iterator();
            while (ia.hasNext()) {
                if (!jsonEquals(ia.next(), ib.next())) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    public static boolean numericEquals(Number a, Number b) {
        if (isIntegral(a) && isIntegral(b)) {
            return a.longValue() == b.longValue();
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
        }
        return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
    }

    public static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        if (isIntegral(n)) {
            return BigDecimal.valueOf(n.longValue());
        }
        return new BigDecimal(n.toString());
    }

    /** Parses a number from a Number or a numeric string; returns null when not numeric. */
    public static BigDecimal asNumber(Object value) {
        if (value instanceof Number n) {
            return toBigDecimal(n);
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    /** Text form used for string interpolation: null becomes "", 500.0 becomes "500". */
    public static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            BigDecimal bd = toBigDecimal((Number) value).stripTrailingZeros();
            return bd.scale() < 0 ? bd.setScale(0).toPlainString() : bd.toPlainString();
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return Json.write(value);
        }
        return String.valueOf(value);
    }

    private static int capacity(int size) {
        return (int) (size / 0.75f) + 1;
    }
}
