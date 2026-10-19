package dev.orderflow.order.outbox;

import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOutboxSender implements OutboxSender {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    public KafkaOutboxSender(KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void send(OutboxMessage message) throws Exception {
        kafkaTemplate.send(message.destination(), message.messageKey(), message.payload())
                .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
