package dev.orderflow.order.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.orderflow.order.application.OrderQueryService;
import dev.orderflow.order.web.CorrelationIdFilter;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderQueryController {

    private final OrderQueryService service;

    public OrderQueryController(OrderQueryService service) {
        this.service = service;
    }

    @GetMapping("/{orderId}")
    ResponseEntity<OrderDetailsResponse> getOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        return ResponseEntity.ok(service.getOrder(orderId, jwt.getSubject(), isOperator(authentication), correlationId));
    }

    @GetMapping("/{orderId}/history")
    ResponseEntity<OrderHistoryResponse> getHistory(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        return ResponseEntity.ok(service.getHistory(orderId, jwt.getSubject(), isOperator(authentication), correlationId));
    }

    private boolean isOperator(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATOR"));
    }
}
