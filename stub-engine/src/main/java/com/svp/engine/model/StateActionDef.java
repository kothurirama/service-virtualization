package com.svp.engine.model;

/**
 * Session-state side effect of a stub.
 *
 * <ul>
 *   <li>{@code PUT}: store {@code value} (default: the rendered response body) under {@code key}.
 *       Rendered after the response, so {@code {{response.body.x}}} is available.</li>
 *   <li>{@code GET}: look up {@code key}; 404 (or {@code not_found_response}) when missing.
 *       Default response body is the stored value.</li>
 *   <li>{@code MERGE}: RFC 7386 merge-patch {@code value} (default: request body) into the stored value.</li>
 *   <li>{@code DELETE}: remove {@code key}; 404 when missing.</li>
 *   <li>{@code LIST}: all values in {@code collection} as {@code {{state.values}}} / {@code {{state.count}}}.</li>
 * </ul>
 */
public record StateActionDef(
        String op,
        String collection,
        String key,
        Object value) {
}
