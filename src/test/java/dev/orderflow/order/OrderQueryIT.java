package dev.orderflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.orderflow.order.api.CreateOrderRequest;
import dev.orderflow.order.api.OrderHistoryResponse;
import dev.orderflow.order.application.OrderCreationService;
import dev.orderflow.order.application.OrderNotFoundException;
import dev.orderflow.order.application.OrderQueryService;
import io.micrometer.core.instrument.MeterRegistry;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "orderflow.outbox.scheduling-enabled=false")
class OrderQueryIT {

    private static final UUID PRODUCT_A = UUID.fromString("22222222-2222-4222-8222-222222222221");
    private static final UUID PRODUCT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String CORRELATION_ID = "33333333-3333-4333-8333-333333333333";

    @Autowired
    private OrderCreationService creationService;

    @Autowired
    private OrderQueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("delete from idempotency_records");
        jdbc.update("delete from outbox_messages");
        jdbc.update("delete from order_status_history");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    @Test
    void returnsCurrentStateAndPriceSnapshotsToTheOwner() {
        var created = create("owner-1", "query-details");

        var response = queryService.getOrder(created.orderId(), "owner-1", false, CORRELATION_ID);

        assertThat(response.orderId()).isEqualTo(created.orderId());
        assertThat(response.status()).isEqualTo("INVENTORY_PENDING");
        assertThat(response.currency()).isEqualTo("BRL");
        assertThat(response.totalAmount()).isEqualByComparingTo("548.90");
        assertThat(response.items())
                .extracting(item -> item.productId())
                .containsExactly(PRODUCT_A, PRODUCT_B);
        assertThat(response.items().get(0).productName()).isEqualTo("Suporte para notebook");
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("149.90");
    }

    @Test
    void hidesAnExistingOrderFromAnotherCustomerLikeAMissingOrder() {
        var created = create("owner-2", "non-disclosure");
        UUID missingOrderId = UUID.fromString("99999999-9999-4999-8999-999999999999");

        assertThatThrownBy(() -> queryService.getOrder(
                created.orderId(), "another-customer", false, CORRELATION_ID))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("Order was not found");
        assertThatThrownBy(() -> queryService.getOrder(
                missingOrderId, "another-customer", false, CORRELATION_ID))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("Order was not found");
    }

    @Test
    void allowsAnOperatorToReadAnotherCustomersOrder() {
        var created = create("owner-3", "operator-access");

        var response = queryService.getOrder(created.orderId(), "operator-1", true, CORRELATION_ID);

        assertThat(response.orderId()).isEqualTo(created.orderId());
    }

    @Test
    void returnsImmutableHistoryInSequenceOrder() {
        var created = create("owner-4", "ordered-history");
        OffsetDateTime createdAt = created.createdAt();
        insertTransition(created.orderId(), 3, "CONFIRMED", createdAt.plusSeconds(20));
        insertTransition(created.orderId(), 2, "PAYMENT_PENDING", createdAt.plusSeconds(10));

        var response = queryService.getHistory(created.orderId(), "owner-4", false, CORRELATION_ID);

        assertThat(response.transitions())
                .extracting(OrderHistoryResponse.Transition::sequenceNumber)
                .containsExactly(1, 2, 3);
        assertThat(response.transitions())
                .extracting(OrderHistoryResponse.Transition::status)
                .containsExactly("INVENTORY_PENDING", "PAYMENT_PENDING", "CONFIRMED");
        assertThatThrownBy(() -> response.transitions().add(response.transitions().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(count("order_status_history")).isEqualTo(3);
    }

    @Test
    void recordsQueryOutcomeMetrics() {
        var created = create("owner-5", "query-metrics");

        queryService.getOrder(created.orderId(), "owner-5", false, CORRELATION_ID);
        assertThatThrownBy(() -> queryService.getHistory(
                created.orderId(), "another-customer", false, CORRELATION_ID))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(meterRegistry.get("orderflow.orders.query")
                .tag("operation", "detail")
                .tag("outcome", "success")
                .counter()
                .count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("orderflow.orders.query")
                .tag("operation", "history")
                .tag("outcome", "not_found")
                .counter()
                .count()).isGreaterThanOrEqualTo(1);
    }

    private dev.orderflow.order.api.OrderResponse create(String customerId, String idempotencyKey) {
        return creationService.create(
                customerId,
                idempotencyKey,
                CORRELATION_ID,
                new CreateOrderRequest(List.of(
                        new CreateOrderRequest.Item(PRODUCT_A, 1),
                        new CreateOrderRequest.Item(PRODUCT_B, 1))));
    }

    private void insertTransition(UUID orderId, int sequenceNumber, String status, OffsetDateTime occurredAt) {
        jdbc.update("""
                insert into order_status_history
                    (transition_id, order_id, sequence_number, status, occurred_at)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, sequenceNumber, status, occurredAt);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
