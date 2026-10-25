package com.svp.engine.model;

import java.util.Map;

/**
 * Response template. Any string may contain {@code {{expression}}} placeholders; a string that is
 * exactly one placeholder keeps the expression's JSON type (number, object, ...).
 */
public record ResponseTemplateDef(
        Integer statusCode,
        Map<String, String> headers,
        Object body) {
}
