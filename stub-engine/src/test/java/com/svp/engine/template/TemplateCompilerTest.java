package com.svp.engine.template;

import com.svp.engine.match.PathPattern;
import com.svp.engine.match.RequestView;
import com.svp.engine.model.VirtualRequest;
import com.svp.engine.state.SessionStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateCompilerTest {

    private final PathPattern path = PathPattern.parse("/customers/{customerId}/accounts/{id}");
    private final SessionStateStore store = SessionStateStore.withDefaults();

    private TemplateContext ctx(Object body) {
        RequestView req = RequestView.of(new VirtualRequest("t1", "HTTP", "POST",
                "/customers/C-7/accounts/ACC-9?expand=true", Map.of("X-Trace", "abc"), body));
        return new TemplateContext(req, path, store.getOrCreate("t1"));
    }

    private Object render(Object template, Object body) {
        return TemplateCompiler.compile(template, path, false).render(ctx(body));
    }

    @Test
    void singlePlaceholderKeepsJsonType() {
        Map<String, Object> body = Map.of("amount", 500.0, "tags", List.of("a"), "nested", Map.of("x", 1));
        assertThat(render("{{request.body.amount}}", body)).isEqualTo(500.0);
        assertThat(render("{{request.body.tags}}", body)).isEqualTo(List.of("a"));
        assertThat(render("{{ request.body.nested }}", body)).isEqualTo(Map.of("x", 1));
    }

    @Test
    void interpolationProducesText() {
        assertThat(render("acct {{request.path.id}} of {{path.customerId}} = {{request.body.amount}}",
                Map.of("amount", 500.0))).isEqualTo("acct ACC-9 of C-7 = 500");
        assertThat(render("{{request.headers.x-trace}}/{{request.query.expand}}/{{request.method}}", null))
                .isEqualTo("abc/true/POST");
    }

    @Test
    void fallbackOperatorReturnsFirstPresentValue() {
        assertThat(render("{{request.body.id ?: 'none'}}", Map.of())).isEqualTo("none");
        assertThat(render("{{request.body.id ?: 'none'}}", Map.of("id", "X"))).isEqualTo("X");
        assertThat(render("{{request.body.missing ?: 0}}", Map.of())).isEqualTo(0L);
    }

    @Test
    void generatedIdsArePerSessionAndMemoisedWithinOneRequest() {
        TemplateContext c = ctx(null);
        Template t = TemplateCompiler.compile(Map.of("a", "{{generate.id:ACC}}", "b", "{{generate.id:ACC}}"), path, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) t.render(c);
        assertThat(out.get("a")).isEqualTo("ACC-1001").isEqualTo(out.get("b"));
        assertThat(TemplateCompiler.compile("{{generate.id:ACC}}", path, false).render(ctx(null))).isEqualTo("ACC-1002");
    }

    @Test
    void mergeCopiesFieldsThenExplicitKeysOverride() {
        Object out = render(Map.of("$merge", "{{request.body}}", "status", "ACTIVE"),
                Map.of("holder", "Alice", "status", "PENDING"));
        assertThat(out).isEqualTo(Map.of("holder", "Alice", "status", "ACTIVE"));
    }

    @Test
    void constantSubtreesAreFrozenAndShared() {
        Template t = TemplateCompiler.compile(Map.of("a", List.of(1, 2), "b", "static"), path, false);
        assertThat(t.isConstant()).isTrue();
        assertThat(t.render(ctx(null))).isSameAs(t.render(ctx(null)));
    }

    @Test
    void errorsAreCaughtAtCompileTime() {
        assertThatThrownBy(() -> TemplateCompiler.compile("{{request.path.nope}}", path, false))
                .hasMessageContaining("unknown path variable 'nope'");
        assertThatThrownBy(() -> TemplateCompiler.compile("{{response.body}}", path, false))
                .hasMessageContaining("only available in state_action");
        assertThatThrownBy(() -> TemplateCompiler.compile("{{whatever}}", path, false))
                .hasMessageContaining("unknown template expression");
        assertThatThrownBy(() -> TemplateCompiler.compile("{{request.body.a", path, false))
                .hasMessageContaining("unclosed");
        assertThatThrownBy(() -> TemplateCompiler.compile("{{generate.id:bad-prefix!}}", path, false))
                .hasMessageContaining("prefix");
    }
}
