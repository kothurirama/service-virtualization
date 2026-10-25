package com.svp.engine.match;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueMatcherTest {

    private static ValueMatcher body(Object spec) {
        return ValueMatcher.compile(spec, false, false);
    }

    @Test
    void typedEqualityWithNumericLeniency() {
        assertThat(body(500).test(500.0)).isTrue();
        assertThat(body(500).test("500")).isFalse();
        assertThat(body("USD").test("USD")).isTrue();
        assertThat(body(true).test(true)).isTrue();
        assertThat(body(Map.of("a", 1)).test(Map.of("a", 1.0))).isTrue();
        assertThat(body("USD").test(JsonPathExpr.MISSING)).isFalse();
    }

    @Test
    void operators() {
        assertThat(body(Map.of("matches", "^MERCH_9\\d{3}$")).test("MERCH_9001")).isTrue();
        assertThat(body(Map.of("matches", "^MERCH_9\\d{3}$")).test("MERCH_90012")).isFalse();
        assertThat(body(Map.of("contains", "json")).test("application/json")).isTrue();
        assertThat(body(Map.of("contains", "B")).test(List.of("A", "B"))).isTrue();
        assertThat(body(Map.of("exists", false)).test(JsonPathExpr.MISSING)).isTrue();
        assertThat(body(Map.of("exists", true)).test("x")).isTrue();
        assertThat(body(Map.of("gt", 10000)).test(10000.01)).isTrue();
        assertThat(body(Map.of("gt", 10000)).test(10000)).isFalse();
        assertThat(body(Map.of("gte", 100, "lt", 1000)).test(100)).isTrue();
        assertThat(body(Map.of("gte", 100, "lt", 1000)).test(1000)).isFalse();
        assertThat(body(Map.of("lte", 5)).test("4.5")).isTrue();
        assertThat(body(Map.of("lte", 5)).test("abc")).isFalse();
    }

    @Test
    void textualAndMediaTypeEquality() {
        assertThat(ValueMatcher.compile(1, true, false).test("1")).isTrue();
        ValueMatcher ct = ValueMatcher.compile("application/json", true, true);
        assertThat(ct.test("application/json; charset=UTF-8")).isTrue();
        assertThat(ct.test("Application/JSON")).isTrue();
        assertThat(ct.test("application/xml")).isFalse();
        assertThat(ct.test(null)).isFalse();
    }

    @Test
    void invalidSpecsFailAtCompileTime() {
        assertThatThrownBy(() -> body(Map.of("matches", "(unclosed"))).hasMessageContaining("invalid regex");
        assertThatThrownBy(() -> body(Map.of("gt", "abc"))).hasMessageContaining("needs a number");
        assertThatThrownBy(() -> body(Map.of("exists", "yes"))).hasMessageContaining("true or false");
        assertThatThrownBy(() -> body(Map.of("regex", "x"))).hasMessageContaining("unknown matcher operator");
    }
}
