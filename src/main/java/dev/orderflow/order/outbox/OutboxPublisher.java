package dev.orderflow.order.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final String claimant = UUID.randomUUID().toString();
    private final OutboxRepository repository;
    private final OutboxSender sender;
    private final OutboxMetrics metrics;
    private final OutboxProperties properties;

    public OutboxPublisher(
            OutboxRepository repository,
            OutboxSender sender,
            OutboxMetrics metrics,
            OutboxProperties properties) {
        this.repository = repository;
        this.sender = sender;
        this.metrics = metrics;
        this.properties = properties;
    }

    public void publishPending() {
        for (OutboxMessage message : repository.claimBatch(
                claimant,
                properties.batchSize(),
                properties.claimTimeout())) {
            repository.renewClaims(claimant);
            publish(message);
        }
        metrics.refresh(repository.stats());
    }

    private void publish(OutboxMessage message) {
        metrics.recordAttempt(message);
        try {
            sender.send(message);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            release(message, exception);
            return;
        } catch (Exception exception) {
            release(message, exception);
            return;
        }

        try {
            if (!repository.markPublished(message.messageId(), claimant)) {
                LOGGER.warn(
                        "Outbox acknowledgement was not marked messageId={} messageType={} destination={} attempt={}",
                        message.messageId(),
                        message.eventType(),
                        message.destination(),
                        message.attemptCount());
                return;
            }
            metrics.recordPublished(message, Duration.between(message.createdAt().toInstant(), Instant.now()));
            LOGGER.info(
                    "Outbox message published messageId={} aggregateId={} messageType={} destination={} attempt={}",
                    message.messageId(),
                    message.aggregateId(),
                    message.eventType(),
                    message.destination(),
                    message.attemptCount());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Outbox message was acknowledged but could not be marked messageId={} messageType={} destination={} failureType={}",
                    message.messageId(),
                    message.eventType(),
                    message.destination(),
                    exception.getClass().getSimpleName());
        }
    }

    private void release(OutboxMessage message, Exception exception) {
        String failureType = exception.getClass().getSimpleName();
        repository.releaseAfterFailure(message.messageId(), claimant, failureType);
        metrics.recordFailure(message);
        LOGGER.warn(
                "Outbox publication failed messageId={} messageType={} destination={} attempt={} failureType={}",
                message.messageId(),
                message.eventType(),
                message.destination(),
                message.attemptCount(),
                failureType);
    }
}
