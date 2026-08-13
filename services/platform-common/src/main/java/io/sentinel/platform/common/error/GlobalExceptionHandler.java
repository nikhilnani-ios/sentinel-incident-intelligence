package io.sentinel.platform.common.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.micrometer.tracing.Tracer;

/**
 * Turns exceptions into consistent JSON across every service.
 *
 * <p>Deliberately does not echo exception messages for unexpected failures: a stack trace or SQL
 * fragment in an API response is an information leak. Those go to the logs with the trace id that
 * the caller receives.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest request) {
        log.warn("Domain error [{}]: {}", ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.code(), ex.getMessage(), ex.status().value(), request.getRequestURI(), traceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();

        ApiError body = ApiError.of(
                        "validation_failed",
                        "Request body failed validation",
                        HttpStatus.BAD_REQUEST.value(),
                        request.getRequestURI(),
                        traceId())
                .withViolations(violations);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        "forbidden",
                        "Your role does not permit this action",
                        HttpStatus.FORBIDDEN.value(),
                        request.getRequestURI(),
                        traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = traceId();
        log.error("Unhandled exception on {} (traceId={})", request.getRequestURI(), traceId, ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(
                        "internal_error",
                        "Something went wrong on our side. Quote the trace id when reporting this.",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        request.getRequestURI(),
                        traceId));
    }

    private String traceId() {
        return tracer.currentSpan() == null
                ? "none"
                : tracer.currentSpan().context().traceId();
    }
}
