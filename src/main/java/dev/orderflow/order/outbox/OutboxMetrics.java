package dev.orderflow.order.outbox;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final AtomicLong backlogSize = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("orderflow.outbox.backlog.size", backlogSize);
        registry.gauge("orderflow.outbox.oldest.age.seconds", oldestAgeSeconds);
    }

    public void recordAttempt(OutboxMessage message) {
        counter("orderflow.outbox.publish.attempts", message).increment();
    }

    public void recordFailure(OutboxMessage message) {
        counter("orderflow.outbox.publish.failures", message).increment();
    }

    public void recordPublished(OutboxMessage message, Duration latency) {
        Timer.builder("orderflow.outbox.publish.latency")
                .tag("destination", message.destination())
                .tag("message.type", message.eventType())
                .register(registry)
                .record(latency);
    }

    public void refresh(OutboxRepository.Stats stats) {
        backlogSize.set(stats.backlogSize());
        oldestAgeSeconds.set(stats.oldestAgeSeconds());
    }

    private Counter counter(String name, OutboxMessage message) {
        return Counter.builder(name)
                .tag("destination", message.destination())
                .tag("message.type", message.eventType())
                .register(registry);
    }
}
