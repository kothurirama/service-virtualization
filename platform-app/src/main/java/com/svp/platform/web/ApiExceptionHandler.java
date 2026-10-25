package com.svp.platform.web;

import com.svp.engine.StubValidationException;
import com.svp.engine.state.SessionLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(StubValidationException.class)
    ResponseEntity<ApiError> invalidStub(StubValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_STUB",
                "stub '" + e.stubId() + "' is invalid", e.errors()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String msg = e.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_JSON",
                msg == null ? "request body is not valid JSON" : msg.lines().findFirst().orElse(msg)));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(SessionLimitException.class)
    ResponseEntity<ApiError> limit(SessionLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.of("SESSION_LIMIT", e.getMessage()));
    }
}
