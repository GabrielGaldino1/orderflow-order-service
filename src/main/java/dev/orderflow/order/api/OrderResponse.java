package dev.orderflow.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String status,
        String currency,
        BigDecimal totalAmount,
        OffsetDateTime createdAt) {
}
