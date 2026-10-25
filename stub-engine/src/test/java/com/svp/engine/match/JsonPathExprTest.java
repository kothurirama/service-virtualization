package com.svp.engine.match;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPathExprTest {

    private final Map<String, Object> doc = Map.of(
            "currency", "USD",
            "amount", 150.0,
            "card", Map.of("network", "VISA", "holder", Map.of("country", "IN")),
            "items", List.of(Map.of("sku", "A", "qty", 1), Map.of("sku", "B", "qty", 2)),
            "weird key", "ok");

    @Test
    void fieldsIndexesAndBrackets() {
        assertThat(JsonPathExpr.compile("$.currency").read(doc)).isEqualTo("USD");
        assertThat(JsonPathExpr.compile("$.card.network").read(doc)).isEqualTo("VISA");
        assertThat(JsonPathExpr.compile("$['card']['holder'].country").read(doc)).isEqualTo("IN");
        assertThat(JsonPathExpr.compile("$.items[1].sku").read(doc)).isEqualTo("B");
        assertThat(JsonPathExpr.compile("$.items[-1].qty").read(doc)).isEqualTo(2);
        assertThat(JsonPathExpr.compile("$['weird key']").read(doc)).isEqualTo("ok");
        assertThat(JsonPathExpr.compile("$").read(doc)).isSameAs(doc);
    }

    @Test
    void missingIsDistinctFromNull() {
        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("x", null);
        assertThat(JsonPathExpr.compile("$.x").read(withNull)).isNull();
        assertThat(JsonPathExpr.compile("$.y").read(withNull)).isSameAs(JsonPathExpr.MISSING);
        assertThat(JsonPathExpr.compile("$.items[9]").read(doc)).isSameAs(JsonPathExpr.MISSING);
        assertThat(JsonPathExpr.compile("$.currency.deeper").read(doc)).isSameAs(JsonPathExpr.MISSING);
        assertThat(JsonPathExpr.compile("$.a").read("not json")).isSameAs(JsonPathExpr.MISSING);
    }

    @Test
    void wildcardAndDeepScanAreMultiValued() {
        JsonPathExpr all = JsonPathExpr.compile("$.items[*].sku");
        assertThat(all.isMultiValued()).isTrue();
        List<Object> out = new ArrayList<>();
        all.readAll(doc, out);
        assertThat(out).containsExactly("A", "B");

        List<Object> deep = new ArrayList<>();
        JsonPathExpr.compile("$..country").readAll(doc, deep);
        assertThat(deep).containsExactly("IN");

        List<Object> star = new ArrayList<>();
        JsonPathExpr.compile("$.card.*").readAll(doc, star);
        assertThat(star).hasSize(2);
    }

    @Test
    void invalidExpressionsAreRejectedAtCompileTime() {
        assertThatThrownBy(() -> JsonPathExpr.compile("currency")).hasMessageContaining("must start with '$'");
        assertThatThrownBy(() -> JsonPathExpr.compile("$.items[?(@.qty>1)]")).hasMessageContaining("unsupported");
        assertThatThrownBy(() -> JsonPathExpr.compile("$.items[0")).hasMessageContaining("unclosed");
        assertThatThrownBy(() -> JsonPathExpr.compile("$..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPathExpr.compile("$.a..")).isInstanceOf(IllegalArgumentException.class);
    }
}
