package dev.orderflow.order.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.orderflow.order.api.CreateOrderRequest;
import dev.orderflow.order.api.OrderResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderCreationService {

    static final String INITIAL_STATUS = "INVENTORY_PENDING";
    static final String CURRENCY = "BRL";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String inventoryCommandsTopic;
    private final MeterRegistry meterRegistry;

    public OrderCreationService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${orderflow.outbox.inventory-commands-topic}") String inventoryCommandsTopic) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.inventoryCommandsTopic = inventoryCommandsTopic;
    }

    @Transactional
    public OrderResponse create(String customerId, String idempotencyKey, String correlationId, CreateOrderRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "technical_failure";
        try {
            String safeKey = validateIdempotencyKey(idempotencyKey);
            List<NormalizedItem> items = normalize(request);
            String requestHash = hash(items);

            lockIdempotencyScope(customerId, safeKey);
            StoredIdempotency existing = findIdempotency(customerId, safeKey);
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) {
                    outcome = "conflict";
                    throw new IdempotencyConflictException();
                }
                outcome = "replayed";
                return findOrder(existing.orderId());
            }

            Map<UUID, ProductSnapshot> products = loadProducts(items);
            List<UUID> invalidProducts = items.stream()
                    .map(NormalizedItem::productId)
                    .filter(id -> !products.containsKey(id) || !products.get(id).active())
                    .toList();
            if (!invalidProducts.isEmpty()) {
                outcome = "invalid_products";
                throw new InvalidProductsException(invalidProducts);
            }

            OffsetDateTime now = OffsetDateTime.ofInstant(
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                    ZoneOffset.UTC);
            UUID orderId = UUID.randomUUID();
            BigDecimal total = items.stream()
                    .map(item -> products.get(item.productId()).unitPrice()
                            .multiply(BigDecimal.valueOf(item.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2);

            persistOrder(orderId, customerId, total, now, items, products);
            persistOutbox(orderId, correlationId, now, items);
            jdbc.update("""
                    insert into idempotency_records
                        (customer_id, idempotency_key, request_hash, order_id, created_at)
                    values (?, ?, ?, ?, ?)
                    """, customerId, safeKey, requestHash, orderId, now);

            outcome = "created";
            return new OrderResponse(orderId, INITIAL_STATUS, CURRENCY, total, now);
        } catch (InvalidOrderRequestException exception) {
            outcome = "invalid_request";
            throw exception;
        } finally {
            Counter.builder("orderflow.orders.creation")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
            sample.stop(Timer.builder("orderflow.orders.creation.duration").register(meterRegistry));
        }
    }

    private String validateIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty() || key.length() > 128) {
            throw new InvalidOrderRequestException("Idempotency-Key must contain between 1 and 128 characters");
        }
        return key;
    }

    static List<NormalizedItem> normalize(CreateOrderRequest request) {
        Map<UUID, Integer> consolidated = new LinkedHashMap<>();
        try {
            request.items().stream()
                    .sorted((left, right) -> left.productId().compareTo(right.productId()))
                    .forEach(item -> consolidated.merge(item.productId(), item.quantity(), Math::addExact));
        } catch (ArithmeticException exception) {
            throw new InvalidOrderRequestException("Consolidated quantity cannot exceed 10000 per product");
        }
        List<NormalizedItem> normalized = consolidated.entrySet().stream()
                .map(entry -> new NormalizedItem(entry.getKey(), entry.getValue()))
                .toList();
        if (normalized.stream().anyMatch(item -> item.quantity() > 10000)) {
            throw new InvalidOrderRequestException("Consolidated quantity cannot exceed 10000 per product");
        }
        return normalized;
    }

    static String hash(List<NormalizedItem> items) {
        String canonical = items.stream()
                .map(item -> item.productId() + ":" + item.quantity())
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void lockIdempotencyScope(String customerId, String idempotencyKey) {
        String scope = customerId.length() + ":" + customerId + idempotencyKey;
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                scope);
    }

    private StoredIdempotency findIdempotency(String customerId, String idempotencyKey) {
        List<StoredIdempotency> matches = jdbc.query(
                "select request_hash, order_id from idempotency_records where customer_id = ? and idempotency_key = ?",
                (rs, rowNum) -> new StoredIdempotency(rs.getString("request_hash"), rs.getObject("order_id", UUID.class)),
                customerId,
                idempotencyKey);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private OrderResponse findOrder(UUID orderId) {
        return jdbc.queryForObject("""
                select order_id, status, currency, total_amount, created_at
                from orders where order_id = ?
                """, (rs, rowNum) -> new OrderResponse(
                        rs.getObject("order_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("currency").trim(),
                        rs.getBigDecimal("total_amount"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                orderId);
    }

    private Map<UUID, ProductSnapshot> loadProducts(List<NormalizedItem> items) {
        List<UUID> ids = items.stream().map(NormalizedItem::productId).toList();
        var parameters = new MapSqlParameterSource("ids", ids);
        Map<UUID, ProductSnapshot> products = new LinkedHashMap<>();
        namedJdbc.query("""
                select product_id, name, unit_price, active
                from product_snapshot where product_id in (:ids)
                """, parameters, rs -> {
                    UUID id = rs.getObject("product_id", UUID.class);
                    products.put(id, new ProductSnapshot(
                            id,
                            rs.getString("name"),
                            rs.getBigDecimal("unit_price"),
                            rs.getBoolean("active")));
                });
        return products;
    }

    private void persistOrder(
            UUID orderId,
            String customerId,
            BigDecimal total,
            OffsetDateTime now,
            List<NormalizedItem> items,
            Map<UUID, ProductSnapshot> products) {
        jdbc.update("""
                insert into orders (order_id, customer_id, status, currency, total_amount, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, orderId, customerId, INITIAL_STATUS, CURRENCY, total, now);
        for (NormalizedItem item : items) {
            ProductSnapshot product = products.get(item.productId());
            BigDecimal lineTotal = product.unitPrice().multiply(BigDecimal.valueOf(item.quantity())).setScale(2);
            jdbc.update("""
                    insert into order_items
                        (order_id, product_id, product_name, unit_price, quantity, line_total)
                    values (?, ?, ?, ?, ?, ?)
                    """, orderId, item.productId(), product.name(), product.unitPrice(), item.quantity(), lineTotal);
        }
        jdbc.update("""
                insert into order_status_history
                    (transition_id, order_id, sequence_number, status, occurred_at)
                values (?, ?, 1, ?, ?)
                """, UUID.randomUUID(), orderId, INITIAL_STATUS, now);
    }

    private void persistOutbox(UUID orderId, String correlationId, OffsetDateTime now, List<NormalizedItem> items) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "InventoryReservationRequested");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", now);
        envelope.put("correlationId", UUID.fromString(correlationId));
        envelope.put("causationId", UUID.fromString(correlationId));
        envelope.put("aggregateId", orderId);
        envelope.put("producer", "order-service");
        List<Map<String, Object>> eventItems = new ArrayList<>();
        for (NormalizedItem item : items) {
            eventItems.add(Map.of("productId", item.productId(), "quantity", item.quantity()));
        }
        envelope.put("payload", Map.of("orderId", orderId, "items", eventItems));
        try {
            jdbc.update("""
                    insert into outbox_messages
                        (message_id, aggregate_id, event_type, event_version, destination, message_key, payload, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, eventId, orderId, "InventoryReservationRequested", 1,
                    inventoryCommandsTopic, orderId.toString(), objectMapper.writeValueAsString(envelope), now);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize the inventory reservation command", exception);
        }
    }

    record NormalizedItem(UUID productId, int quantity) {
    }

    private record ProductSnapshot(UUID productId, String name, BigDecimal unitPrice, boolean active) {
    }

    private record StoredIdempotency(String requestHash, UUID orderId) {
    }
}
