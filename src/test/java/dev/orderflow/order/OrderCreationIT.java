package dev.orderflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.orderflow.order.api.CreateOrderRequest;
import dev.orderflow.order.application.IdempotencyConflictException;
import dev.orderflow.order.application.InvalidProductsException;
import dev.orderflow.order.application.OrderCreationService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderCreationIT {

    private static final UUID PRODUCT_A = UUID.fromString("22222222-2222-4222-8222-222222222221");
    private static final UUID PRODUCT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INACTIVE_PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222223");
    private static final UUID MISSING_PRODUCT = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String CORRELATION_ID = "33333333-3333-4333-8333-333333333333";

    @Autowired
    private OrderCreationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("delete from idempotency_records");
        jdbc.update("delete from outbox_messages");
        jdbc.update("delete from order_status_history");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    @Test
    void createsOneOrderWithConsolidatedItemsPriceSnapshotsHistoryAndOutbox() throws Exception {
        var request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_A, 1),
                new CreateOrderRequest.Item(PRODUCT_B, 1),
                new CreateOrderRequest.Item(PRODUCT_A, 1)));

        var response = service.create("customer-1", "create-1", CORRELATION_ID, request);

        assertThat(response.status()).isEqualTo("INVENTORY_PENDING");
        assertThat(response.currency()).isEqualTo("BRL");
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("698.80"));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(2);
        assertThat(count("order_status_history")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);

        Integer consolidatedQuantity = jdbc.queryForObject(
                "select quantity from order_items where order_id = ? and product_id = ?",
                Integer.class,
                response.orderId(),
                PRODUCT_A);
        assertThat(consolidatedQuantity).isEqualTo(2);

        String payload = jdbc.queryForObject("select payload from outbox_messages", String.class);
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.get("eventType").asString()).isEqualTo("InventoryReservationRequested");
        assertThat(event.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(event.get("producer").asString()).isEqualTo("order-service");
        assertThat(event.get("correlationId").asString()).isEqualTo(CORRELATION_ID);
        assertThat(event.get("causationId").asString()).isEqualTo(CORRELATION_ID);
        assertThat(event.get("aggregateId").asString()).isEqualTo(response.orderId().toString());
        assertThat(event.get("payload").get("orderId").asString()).isEqualTo(response.orderId().toString());
        assertThat(event.get("payload").get("items").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select destination from outbox_messages", String.class))
                .isEqualTo("orderflow-inventory-commands");
    }

    @Test
    void replaysAnEquivalentNormalizedRequestWithoutCreatingAnotherOrder() {
        var first = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_A, 1),
                new CreateOrderRequest.Item(PRODUCT_A, 1),
                new CreateOrderRequest.Item(PRODUCT_B, 1)));
        var equivalent = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_B, 1),
                new CreateOrderRequest.Item(PRODUCT_A, 2)));

        var created = service.create("customer-2", "same-key", CORRELATION_ID, first);
        var replayed = service.create("customer-2", "same-key", CORRELATION_ID, equivalent);

        assertThat(replayed).isEqualTo(created);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);
    }

    @Test
    void rejectsChangedContentForTheSameCustomerAndKey() {
        service.create("customer-3", "conflicting-key", CORRELATION_ID, request(PRODUCT_A, 1));

        assertThatThrownBy(() -> service.create(
                "customer-3", "conflicting-key", CORRELATION_ID, request(PRODUCT_A, 2)))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);
    }

    @Test
    void scopesTheSameIdempotencyKeyByCustomer() {
        var first = service.create("customer-a", "shared-key", CORRELATION_ID, request(PRODUCT_A, 1));
        var second = service.create("customer-b", "shared-key", CORRELATION_ID, request(PRODUCT_A, 1));

        assertThat(second.orderId()).isNotEqualTo(first.orderId());
        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    void reportsAllInvalidProductsAndRollsBackEveryBusinessRow() {
        var invalidRequest = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(MISSING_PRODUCT, 1),
                new CreateOrderRequest.Item(INACTIVE_PRODUCT, 1)));

        assertThatThrownBy(() -> service.create(
                "customer-4", "invalid-products", CORRELATION_ID, invalidRequest))
                .isInstanceOfSatisfying(InvalidProductsException.class, exception ->
                        assertThat(exception.productIds())
                                .containsExactlyInAnyOrder(MISSING_PRODUCT, INACTIVE_PRODUCT));

        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_records")).isZero();
        assertThat(count("outbox_messages")).isZero();
    }

    @Test
    void serializesConcurrentRequestsForTheSameIdempotencyScope() throws Exception {
        int calls = 8;
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(calls);
        try {
            var futures = IntStream.range(0, calls)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return service.create(
                                "concurrent-customer",
                                "concurrent-key",
                                CORRELATION_ID,
                                request(PRODUCT_B, 1));
                    }))
                    .toList();

            start.countDown();
            var orderIds = new HashSet<UUID>();
            for (var future : futures) {
                orderIds.add(future.get(20, TimeUnit.SECONDS).orderId());
            }

            assertThat(orderIds).hasSize(1);
            assertThat(count("orders")).isEqualTo(1);
            assertThat(count("idempotency_records")).isEqualTo(1);
            assertThat(count("outbox_messages")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private CreateOrderRequest request(UUID productId, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(productId, quantity)));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
