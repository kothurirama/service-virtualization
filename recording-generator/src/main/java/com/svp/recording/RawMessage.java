package com.svp.recording;

import java.util.Map;

/** One side of a recorded exchange. {@code statusCode} is only set on responses. */
public record RawMessage(Integer statusCode, Map<String, String> headers, Object body) {
}
