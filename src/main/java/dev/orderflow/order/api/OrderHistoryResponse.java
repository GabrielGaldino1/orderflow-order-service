package dev.orderflow.order.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderHistoryResponse(
        UUID orderId,
        List<Transition> transitions) {

    public record Transition(
            UUID transitionId,
            int sequenceNumber,
            String status,
            OffsetDateTime occurredAt) {
    }
}
