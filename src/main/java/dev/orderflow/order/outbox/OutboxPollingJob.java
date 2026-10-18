package dev.orderflow.order.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "orderflow.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollingJob {

    private final OutboxPublisher publisher;

    public OutboxPollingJob(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval:500ms}")
    void publishPending() {
        publisher.publishPending();
    }
}
