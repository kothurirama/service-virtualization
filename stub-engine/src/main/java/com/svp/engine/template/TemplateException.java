package com.svp.engine.template;

/** Invalid template detected at compile (registration) time. */
public class TemplateException extends IllegalArgumentException {
    public TemplateException(String message) {
        super(message);
    }
}
