package com.svp.engine.template;

import com.svp.engine.match.JsonPathExpr;

/** A compiled template expression. Returns {@link JsonPathExpr#MISSING} when the value is absent. */
@FunctionalInterface
public interface Expr {

    Object eval(TemplateContext ctx);

    /** {@code a ?: b ?: c} — the first alternative that is present and not null. */
    static Expr firstPresent(Expr[] alternatives) {
        return ctx -> {
            for (Expr e : alternatives) {
                Object v = e.eval(ctx);
                if (v != null && v != JsonPathExpr.MISSING) {
                    return v;
                }
            }
            return JsonPathExpr.MISSING;
        };
    }
}
