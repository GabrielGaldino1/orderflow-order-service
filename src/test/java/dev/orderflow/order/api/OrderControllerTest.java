package dev.orderflow.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.orderflow.order.application.IdempotencyConflictException;
import dev.orderflow.order.application.InvalidProductsException;
import dev.orderflow.order.application.OrderCreationService;
import dev.orderflow.order.config.SecurityConfiguration;
import dev.orderflow.order.web.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@WebMvcTest(OrderController.class)
@Import({SecurityConfiguration.class, CorrelationIdFilter.class, ApiExceptionHandler.class, OrderControllerTest.Metrics.class})
class OrderControllerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-4222-8222-222222222221");
    private static final String CORRELATION_ID = "33333333-3333-4333-8333-333333333333";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderCreationService service;

    @Test
    void returnsCreatedResourceAndLocationForACustomer() throws Exception {
        when(service.create(eq("customer-123"), eq("request-1"), eq(CORRELATION_ID), any()))
                .thenReturn(new OrderResponse(
                        ORDER_ID,
                        "INVENTORY_PENDING",
                        "BRL",
                        new BigDecimal("299.80"),
                        OffsetDateTime.parse("2026-09-27T20:00:00Z")));

        mvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token.subject("customer-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("Idempotency-Key", "request-1")
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(2)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + ORDER_ID))
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("INVENTORY_PENDING"))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.totalAmount").value(299.80));
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedOperatorWithoutCustomerRole() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token.subject("operator-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsEveryInvalidProduct() throws Exception {
        UUID anotherProduct = UUID.fromString("99999999-9999-4999-8999-999999999999");
        when(service.create(eq("customer-123"), eq("request-2"), anyString(), any()))
                .thenThrow(new InvalidProductsException(List.of(PRODUCT_ID, anotherProduct)));

        mvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token.subject("customer-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("Idempotency-Key", "request-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://orderflow.dev/problems/invalid-products"))
                .andExpect(jsonPath("$.invalidProductIds.length()").value(2));
    }

    @Test
    void rejectsConflictingIdempotencyReuse() throws Exception {
        when(service.create(eq("customer-123"), eq("request-3"), anyString(), any()))
                .thenThrow(new IdempotencyConflictException());

        mvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token.subject("customer-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("Idempotency-Key", "request-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://orderflow.dev/problems/idempotency-conflict"));
    }

    @Test
    void validatesTheHeaderAndBodyContract() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://orderflow.dev/problems/missing-header"));
    }

    private String requestBody(int quantity) {
        return """
                {"items":[{"productId":"%s","quantity":%d}]}
                """.formatted(PRODUCT_ID, quantity);
    }

    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
