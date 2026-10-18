package dev.orderflow.order.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orderflow.outbox")
public record OutboxProperties(
        int batchSize,
        Duration claimTimeout,
        Duration sendTimeout) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("orderflow.outbox.batch-size must be positive");
        }
        if (claimTimeout.isNegative() || claimTimeout.isZero()) {
            throw new IllegalArgumentException("orderflow.outbox.claim-timeout must be positive");
        }
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("orderflow.outbox.send-timeout must be positive");
        }
        if (claimTimeout.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "orderflow.outbox.claim-timeout must be greater than orderflow.outbox.send-timeout");
        }
    }
}
