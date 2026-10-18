package dev.orderflow.order.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.orderflow.order.api.OrderDetailsResponse;
import dev.orderflow.order.api.OrderHistoryResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class OrderQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderQueryService.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;

    public OrderQueryService(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrder(
            UUID orderId,
            String requesterId,
            boolean operator,
            String correlationId) {
        return observe("detail", orderId, correlationId, () -> {
            StoredOrder order = loadAuthorizedOrder(orderId, requesterId, operator);
            List<OrderDetailsResponse.Item> items = jdbc.query("""
                    select product_id, product_name, unit_price, quantity, line_total
                    from order_items
                    where order_id = ?
                    order by product_id
                    """, (rs, rowNum) -> new OrderDetailsResponse.Item(
                            rs.getObject("product_id", UUID.class),
                            rs.getString("product_name"),
                            rs.getBigDecimal("unit_price"),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("line_total")),
                    orderId);
            return new OrderDetailsResponse(
                    order.orderId(),
                    order.status(),
                    order.currency(),
                    order.totalAmount(),
                    order.createdAt(),
                    List.copyOf(items));
        });
    }

    @Transactional(readOnly = true)
    public OrderHistoryResponse getHistory(
            UUID orderId,
            String requesterId,
            boolean operator,
            String correlationId) {
        return observe("history", orderId, correlationId, () -> {
            loadAuthorizedOrder(orderId, requesterId, operator);
            List<OrderHistoryResponse.Transition> transitions = jdbc.query("""
                    select transition_id, sequence_number, status, occurred_at
                    from order_status_history
                    where order_id = ?
                    order by sequence_number asc
                    """, (rs, rowNum) -> new OrderHistoryResponse.Transition(
                            rs.getObject("transition_id", UUID.class),
                            rs.getInt("sequence_number"),
                            rs.getString("status"),
                            rs.getObject("occurred_at", OffsetDateTime.class)),
                    orderId);
            return new OrderHistoryResponse(orderId, List.copyOf(transitions));
        });
    }

    private StoredOrder loadAuthorizedOrder(UUID orderId, String requesterId, boolean operator) {
        List<StoredOrder> matches = jdbc.query("""
                select order_id, status, currency, total_amount, created_at
                from orders
                where order_id = ? and (customer_id = ? or ?)
                """, (rs, rowNum) -> new StoredOrder(
                        rs.getObject("order_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("currency").trim(),
                        rs.getBigDecimal("total_amount"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                orderId,
                requesterId,
                operator);
        if (matches.isEmpty()) {
            throw new OrderNotFoundException();
        }
        return matches.get(0);
    }

    private <T> T observe(String operation, UUID orderId, String correlationId, Supplier<T> query) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "technical_failure";
        try {
            T response = query.get();
            outcome = "success";
            return response;
        } catch (OrderNotFoundException exception) {
            outcome = "not_found";
            throw exception;
        } finally {
            Counter.builder("orderflow.orders.query")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
            sample.stop(Timer.builder("orderflow.orders.query.duration")
                    .tag("operation", operation)
                    .register(meterRegistry));
            LOGGER.info(
                    "Order query operation={} orderId={} outcome={} correlationId={}",
                    operation,
                    orderId,
                    outcome,
                    correlationId);
        }
    }

    private record StoredOrder(
            UUID orderId,
            String status,
            String currency,
            BigDecimal totalAmount,
            OffsetDateTime createdAt) {
    }
}
