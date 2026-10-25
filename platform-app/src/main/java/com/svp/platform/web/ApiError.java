package com.svp.platform.web;

import java.util.List;

/** Uniform error body for the control plane. */
public record ApiError(String error, String message, List<String> details) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null);
    }
}
