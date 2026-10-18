package dev.orderflow.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderDetailsResponse(
        UUID orderId,
        String status,
        String currency,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        List<Item> items) {

    public record Item(
            UUID productId,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal) {
    }
}
