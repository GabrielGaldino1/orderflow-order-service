package dev.orderflow.order.api;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.orderflow.order.application.OrderCreationService;
import dev.orderflow.order.web.CorrelationIdFilter;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderCreationService service;

    public OrderController(OrderCreationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        OrderResponse response = service.create(jwt.getSubject(), idempotencyKey, correlationId, request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + response.orderId())).body(response);
    }
}
