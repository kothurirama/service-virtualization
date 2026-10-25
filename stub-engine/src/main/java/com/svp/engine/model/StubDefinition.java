package com.svp.engine.model;

import java.util.List;
import java.util.Objects;

/**
 * A stub as authored by a user, seeded at startup or synthesised by the recording pipeline.
 * This is the wire format of {@code POST /api/v1/stubs}.
 */
public record StubDefinition(
        String stubId,
        String description,
        Boolean enabled,
        Integer priority,
        List<String> tags,
        StubSource source,
        RequestMatcherDef requestMatcher,
        ResponseTemplateDef responseTemplate,
        StateActionDef stateAction,
        ResponseTemplateDef notFoundResponse) {

    public StubDefinition {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        priority = priority == null ? 0 : priority;
        tags = tags == null ? List.of() : tags.stream().filter(Objects::nonNull).toList();
        source = source == null ? StubSource.MANUAL : source;
    }

    public StubDefinition withEnabled(boolean value) {
        return new StubDefinition(stubId, description, value, priority, tags, source,
                requestMatcher, responseTemplate, stateAction, notFoundResponse);
    }

    public StubDefinition withSource(StubSource value) {
        return new StubDefinition(stubId, description, enabled, priority, tags, value,
                requestMatcher, responseTemplate, stateAction, notFoundResponse);
    }

    public StubDefinition withStubId(String value) {
        return new StubDefinition(value, description, enabled, priority, tags, source,
                requestMatcher, responseTemplate, stateAction, notFoundResponse);
    }
}
