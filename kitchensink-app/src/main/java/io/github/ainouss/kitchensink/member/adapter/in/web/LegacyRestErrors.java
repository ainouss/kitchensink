package io.github.ainouss.kitchensink.member.adapter.in.web;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors for the legacy API: an unreadable request gets 400 {@code {"error": message}}, as in the legacy (R7).
 * Applies to {@link LegacyMemberRestController} only.
 */
@RestControllerAdvice(assignableTypes = LegacyMemberRestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class LegacyRestErrors {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "The request body is not a member in JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> notJson(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(Map.of("error", "The request body must be application/json"));
    }
}
