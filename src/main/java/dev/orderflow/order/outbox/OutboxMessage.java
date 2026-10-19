package dev.orderflow.order.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxMessage(
        UUID messageId,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        String destination,
        String messageKey,
        String payload,
        OffsetDateTime createdAt,
        int attemptCount) {
}
