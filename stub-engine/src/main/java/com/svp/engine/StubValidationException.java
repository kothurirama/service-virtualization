package com.svp.engine;

import java.util.List;

/** A stub definition was rejected at registration. Carries every problem found, not just the first. */
public class StubValidationException extends RuntimeException {

    private final String stubId;
    private final List<String> errors;

    public StubValidationException(String stubId, List<String> errors) {
        super("stub '" + stubId + "' is invalid: " + String.join("; ", errors));
        this.stubId = stubId;
        this.errors = List.copyOf(errors);
    }

    public String stubId() {
        return stubId;
    }

    public List<String> errors() {
        return errors;
    }
}
