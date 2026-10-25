package com.svp.engine.state;

/** Thrown when a session or entry limit would be exceeded; surfaced as HTTP 429. */
public class SessionLimitException extends RuntimeException {
    public SessionLimitException(String message) {
        super(message);
    }
}
