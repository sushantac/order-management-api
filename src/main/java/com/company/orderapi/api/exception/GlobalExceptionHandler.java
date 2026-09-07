package com.company.orderapi.api.exception;

import com.company.orderapi.domain.service.PaymentFailedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR #24 - single place where exceptions become RFC 7807 Problem Details.
 *
 * <p>A Java 21 pattern-matching {@code switch} classifies any exception into a
 * small {@link ErrorSpec} (HTTP status + error-catalog code + hint). Every
 * response carries {@code code} and {@code hint} properties so clients can
 * branch programmatically instead of string-matching messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * PR #26 - method-security denials (e.g. missing scope) must map to 403
     * even though the generic handler below would classify them as 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handle(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "Access denied", "Your token/role lacks the required authority.", ex);
    }

    /**
     * PR #26 - an invalid/expired bearer token surfaces as an authentication
     * error; keep the body in the same Problem Detail style.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handle(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication required", "Send a valid bearer token or API key.", ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception ex) {
        ErrorSpec spec = classify(ex);
        return problem(spec.status(), spec.code(), spec.title(), spec.hint(), ex);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code,
                                                  String title, String hint, Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messageOf(ex));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("hint", hint);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** Error-catalog mapping: one row per exception family. */
    private ErrorSpec classify(Exception ex) {
        return switch (ex) {
            case MethodArgumentNotValidException e ->
                    new ErrorSpec(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                            "Validation failed", "Fix the fields reported in the response body.");
            case ConstraintViolationException e ->
                    new ErrorSpec(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                            "Validation failed", "Fix the fields reported in the response body.");
            case PaymentFailedException e ->
                    new ErrorSpec(HttpStatus.BAD_GATEWAY, "PAYMENT_FAILED",
                            "Payment could not be processed",
                            "Retry with the same Idempotency-Key - no charge was recorded.");
            case DataIntegrityViolationException e ->
                    new ErrorSpec(HttpStatus.CONFLICT, "DATA_CONFLICT",
                            "Data conflict", "The data you sent conflicts with existing records.");
            case OptimisticLockingFailureException e ->
                    new ErrorSpec(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                            "Concurrent modification",
                            "Re-read the resource and retry with the fresh version.");
            case IllegalArgumentException e when String.valueOf(e.getMessage()).contains("Unknown") ->
                    new ErrorSpec(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                            "Resource not found", "The requested resource does not exist.");
            case IllegalArgumentException e ->
                    new ErrorSpec(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                            "Invalid request", "Check the request parameters.");
            case IllegalStateException e
                    when String.valueOf(e.getMessage()).contains("Insufficient stock") ->
                    new ErrorSpec(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                            "Insufficient stock", "Reduce the quantity or restock the product.");
            case IllegalStateException e
                    when String.valueOf(e.getMessage()).contains("cannot be removed") ->
                    new ErrorSpec(HttpStatus.CONFLICT, "RESOURCE_IN_USE",
                            "Resource in use", "Remove dependent records first.");
            default -> new ErrorSpec(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "Internal error", "Please retry; if it persists contact support.");
        };
    }

    private String messageOf(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    /** Immutable classification row produced by the pattern-matching switch. */
    record ErrorSpec(HttpStatus status, String code, String title, String hint) {
    }
}
