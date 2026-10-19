package dev.orderflow.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

import dev.orderflow.order.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "orderflow.outbox.scheduling-enabled=false")
class OutboxPublisherIT {

    private static final String TOPIC = "orderflow-outbox-integration-test";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private OutboxSender sender;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OutboxMetrics metrics;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private KafkaContainer kafka;

    @BeforeEach
    void cleanOutbox() {
        jdbc.update("delete from outbox_messages");
    }

    @Test
    void publishesToKafkaAndMarksTheAcknowledgedMessage() {
        String topic = topic("publish-success");
        UUID messageId = insertMessage(topic, "published-once");

        try (KafkaConsumer<String, String> consumer = consumer(topic, "publish-success")) {
            publisher.publishPending();

            var records = awaitRecords(consumer, 1);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo("published-once");
            assertThat(records.get(0).value()).contains(messageId.toString());
        }

        assertThat(jdbc.queryForObject(
                "select published_at is not null from outbox_messages where message_id = ?",
                Boolean.class,
                messageId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select attempt_count from outbox_messages where message_id = ?",
                Integer.class,
                messageId)).isEqualTo(1);
        assertThat(meterRegistry.get("orderflow.outbox.publish.attempts").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void republishesAfterBrokerAckWhenTheDatabaseMarkWasLost() throws Exception {
        String topic = topic("crash-recovery");
        UUID messageId = insertMessage(topic, "crash-window");

        try (KafkaConsumer<String, String> consumer = consumer(topic, "crash-recovery")) {
            OutboxMessage claimed = repository.claimBatch("crashed-instance", 1, Duration.ofSeconds(30)).get(0);
            sender.send(claimed);
            jdbc.update("""
                    update outbox_messages
                    set claimed_at = current_timestamp - interval '2 minutes'
                    where message_id = ?
                    """, messageId);

            publisher.publishPending();

            var records = awaitRecords(consumer, 2);
            assertThat(records).hasSize(2);
            assertThat(records).allSatisfy(record -> assertThat(record.key()).isEqualTo("crash-window"));
        }

        assertThat(jdbc.queryForObject(
                "select attempt_count from outbox_messages where message_id = ?",
                Integer.class,
                messageId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select published_at is not null from outbox_messages where message_id = ?",
                Boolean.class,
                messageId)).isTrue();
    }

    @Test
    void releasesAfterSendFailureAndPublishesWhenTheBrokerRecovers() {
        String topic = topic("broker-recovery");
        UUID messageId = insertMessage(topic, "broker-recovery");
        AtomicInteger attempts = new AtomicInteger();
        OutboxSender temporarilyUnavailable = message -> {
            if (attempts.getAndIncrement() == 0) {
                throw new TimeoutException("simulated Kafka outage");
            }
            sender.send(message);
        };
        OutboxPublisher retryingPublisher = new OutboxPublisher(
                repository, temporarilyUnavailable, metrics, properties);

        retryingPublisher.publishPending();

        Map<String, Object> failedState = jdbc.queryForMap("""
                select published_at, claimed_by, claimed_at, attempt_count, last_error
                from outbox_messages where message_id = ?
                """, messageId);
        assertThat(failedState.get("published_at")).isNull();
        assertThat(failedState.get("claimed_by")).isNull();
        assertThat(failedState.get("claimed_at")).isNull();
        assertThat(failedState.get("attempt_count")).isEqualTo(1);
        assertThat(failedState.get("last_error")).isEqualTo("TimeoutException");

        try (KafkaConsumer<String, String> consumer = consumer(topic, "broker-recovery")) {
            retryingPublisher.publishPending();
            assertThat(awaitRecords(consumer, 1)).hasSize(1);
        }

        assertThat(jdbc.queryForObject(
                "select published_at is not null from outbox_messages where message_id = ?",
                Boolean.class,
                messageId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select attempt_count from outbox_messages where message_id = ?",
                Integer.class,
                messageId)).isEqualTo(2);
    }

    @Test
    void renewsTheRemainingBatchLeaseBeforeEachSend() {
        String topic = topic("lease-renewal");
        insertMessage(topic, "lease-first");
        insertMessage(topic, "lease-second");
        AtomicInteger sends = new AtomicInteger();
        AtomicBoolean secondLeaseWasRenewed = new AtomicBoolean();
        OutboxSender slowBatch = message -> {
            if (sends.incrementAndGet() == 1) {
                jdbc.update("""
                        update outbox_messages
                        set claimed_at = current_timestamp - interval '2 minutes'
                        where published_at is null
                        """);
                return;
            }
            secondLeaseWasRenewed.set(Boolean.TRUE.equals(jdbc.queryForObject("""
                    select claimed_at > current_timestamp - interval '5 seconds'
                    from outbox_messages where message_id = ?
                    """, Boolean.class, message.messageId())));
        };

        new OutboxPublisher(repository, slowBatch, metrics, properties).publishPending();

        assertThat(sends).hasValue(2);
        assertThat(secondLeaseWasRenewed).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_messages where published_at is not null",
                Integer.class)).isEqualTo(2);
    }

    private UUID insertMessage(String topic, String key) {
        UUID messageId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + messageId + "\",\"orderId\":\"" + aggregateId + "\"}";
        jdbc.update("""
                insert into outbox_messages
                    (message_id, aggregate_id, event_type, event_version, destination,
                     message_key, payload, created_at)
                values (?, ?, 'InventoryReservationRequested', 1, ?, ?, ?, current_timestamp)
                """, messageId, aggregateId, topic, key, payload);
        return messageId;
    }

    private String topic(String suffix) {
        return TOPIC + "-" + suffix + "-" + UUID.randomUUID();
    }

    private KafkaConsumer<String, String> consumer(String topic, String groupSuffix) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "orderflow-outbox-it-" + groupSuffix + "-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofMillis(500));
        return consumer;
    }

    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> awaitRecords(
            KafkaConsumer<String, String> consumer,
            int expected) {
        var records = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        return records;
    }
}
