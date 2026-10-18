package dev.orderflow.order.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.orderflow.order.application.OrderNotFoundException;
import dev.orderflow.order.application.OrderQueryService;
import dev.orderflow.order.config.SecurityConfiguration;
import dev.orderflow.order.web.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@WebMvcTest(OrderQueryController.class)
@Import({SecurityConfiguration.class, CorrelationIdFilter.class, ApiExceptionHandler.class,
        OrderQueryControllerTest.Metrics.class})
class OrderQueryControllerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-4222-8222-222222222221");
    private static final UUID TRANSITION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String CORRELATION_ID = "33333333-3333-4333-8333-333333333333";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-27T20:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderQueryService service;

    @Test
    void returnsCurrentOrderToItsOwner() throws Exception {
        when(service.getOrder(eq(ORDER_ID), eq("customer-123"), eq(false), eq(CORRELATION_ID)))
                .thenReturn(details());

        mvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
                        .with(jwt().jwt(token -> token.subject("customer-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("INVENTORY_PENDING"))
                .andExpect(jsonPath("$.items[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.items[0].lineTotal").value(299.80));
    }

    @Test
    void allowsAnOperatorToInspectAnyOrder() throws Exception {
        when(service.getOrder(eq(ORDER_ID), eq("operator-123"), eq(true), eq(CORRELATION_ID)))
                .thenReturn(details());

        mvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
                        .with(jwt().jwt(token -> token.subject("operator-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                        .header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()));
    }

    @Test
    void returnsOrderedHistory() throws Exception {
        when(service.getHistory(eq(ORDER_ID), eq("customer-123"), eq(false), eq(CORRELATION_ID)))
                .thenReturn(new OrderHistoryResponse(
                        ORDER_ID,
                        List.of(new OrderHistoryResponse.Transition(
                                TRANSITION_ID,
                                1,
                                "INVENTORY_PENDING",
                                CREATED_AT))));

        mvc.perform(get("/api/v1/orders/{orderId}/history", ORDER_ID)
                        .with(jwt().jwt(token -> token.subject("customer-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.transitions[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.transitions[0].status").value("INVENTORY_PENDING"))
                .andExpect(jsonPath("$.transitions[0].occurredAt").value("2026-09-27T20:00:00Z"));
    }

    @Test
    void returnsTheSameNotFoundProblemForAnUnauthorizedOrder() throws Exception {
        when(service.getOrder(eq(ORDER_ID), eq("customer-456"), eq(false), eq(CORRELATION_ID)))
                .thenThrow(new OrderNotFoundException());

        mvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
                        .with(jwt().jwt(token -> token.subject("customer-456"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://orderflow.dev/problems/order-not-found"))
                .andExpect(jsonPath("$.detail").value("Order was not found"));
    }

    @Test
    void rejectsUnauthenticatedQueries() throws Exception {
        mvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAuthenticatedUsersWithoutAnAllowedRole() throws Exception {
        mvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
                        .with(jwt().jwt(token -> token.subject("service-123"))))
                .andExpect(status().isForbidden());
    }

    private OrderDetailsResponse details() {
        return new OrderDetailsResponse(
                ORDER_ID,
                "INVENTORY_PENDING",
                "BRL",
                new BigDecimal("299.80"),
                CREATED_AT,
                List.of(new OrderDetailsResponse.Item(
                        PRODUCT_ID,
                        "Suporte para notebook",
                        new BigDecimal("149.90"),
                        2,
                        new BigDecimal("299.80"))));
    }

    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
