package com.svp.engine.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * A compiled response/state template. Compilation happens once at stub registration; rendering
 * walks a small tree of nodes. Subtrees without placeholders are pre-frozen constants and are
 * returned as-is (no copying).
 */
public sealed interface Template {

    Object render(TemplateContext ctx);

    boolean isConstant();

    /** Fixed value (frozen at compile time). */
    record Constant(Object value) implements Template {
        @Override
        public Object render(TemplateContext ctx) {
            return value;
        }

        @Override
        public boolean isConstant() {
            return true;
        }
    }

    /** A string that is exactly one placeholder: keeps the expression's JSON type. */
    record Single(Expr expr) implements Template {
        @Override
        public Object render(TemplateContext ctx) {
            return TemplateContext.orNull(expr.eval(ctx));
        }

        @Override
        public boolean isConstant() {
            return false;
        }
    }

    /** Text with embedded placeholders, e.g. "/api/v1/accounts/{{response.body.account_id}}". */
    record Interpolated(Object[] parts) implements Template {
        @Override
        public Object render(TemplateContext ctx) {
            StringBuilder sb = new StringBuilder(64);
            for (Object part : parts) {
                if (part instanceof Expr e) {
                    sb.append(TemplateContext.text(e.eval(ctx)));
                } else {
                    sb.append((String) part);
                }
            }
            return sb.toString();
        }

        @Override
        public boolean isConstant() {
            return false;
        }
    }

    /**
     * JSON object. An optional {@code "$merge": "{{expr}}"} entry copies an object's fields first,
     * then the explicit keys override them.
     */
    record ObjectNode(String[] keys, Template[] values, Expr merge) implements Template {
        @Override
        public Object render(TemplateContext ctx) {
            Map<String, Object> out = new LinkedHashMap<>(keys.length * 2);
            if (merge != null) {
                Object base = TemplateContext.orNull(merge.eval(ctx));
                if (base instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            }
            for (int i = 0; i < keys.length; i++) {
                out.put(keys[i], values[i].render(ctx));
            }
            return out;
        }

        @Override
        public boolean isConstant() {
            return false;
        }
    }

    record ArrayNode(Template[] items) implements Template {
        @Override
        public Object render(TemplateContext ctx) {
            List<Object> out = new ArrayList<>(items.length);
            for (Template t : items) {
                out.add(t.render(ctx));
            }
            return out;
        }

        @Override
        public boolean isConstant() {
            return false;
        }
    }
}
