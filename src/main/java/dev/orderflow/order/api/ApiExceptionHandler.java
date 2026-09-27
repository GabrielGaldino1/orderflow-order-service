package dev.orderflow.order.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.orderflow.order.application.IdempotencyConflictException;
import dev.orderflow.order.application.InvalidOrderRequestException;
import dev.orderflow.order.application.InvalidProductsException;
import io.micrometer.core.instrument.MeterRegistry;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final MeterRegistry meterRegistry;

    public ApiExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotencyConflict(IdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Idempotency conflict", exception.getMessage(), "idempotency-conflict", Map.of());
    }

    @ExceptionHandler(InvalidProductsException.class)
    ResponseEntity<ProblemDetail> invalidProducts(InvalidProductsException exception) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid products",
                exception.getMessage(),
                "invalid-products",
                Map.of("invalidProductIds", exception.productIds()));
    }

    @ExceptionHandler(InvalidOrderRequestException.class)
    ResponseEntity<ProblemDetail> invalidOrder(InvalidOrderRequestException exception) {
        recordValidation("invalid-order");
        return problem(HttpStatus.BAD_REQUEST, "Invalid order request", exception.getMessage(), "invalid-order", Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException exception) {
        recordValidation("missing-header");
        return problem(
                HttpStatus.BAD_REQUEST,
                "Missing required header",
                exception.getMessage(),
                "missing-header",
                Map.of("header", exception.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception) {
        recordValidation("invalid-body");
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .toList();
        return problem(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "The request contains invalid fields",
                "validation-failed",
                Map.of("errors", errors));
    }

    private void recordValidation(String reason) {
        meterRegistry.counter("orderflow.orders.validation.failures", "reason", reason).increment();
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://orderflow.dev/problems/" + type));
        properties.forEach(problem::setProperty);
        return ResponseEntity.status(status).body(problem);
    }
}
