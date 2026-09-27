package dev.orderflow.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.orderflow.order.api.CreateOrderRequest;

class OrderCreationServiceTest {

    private static final UUID PRODUCT_A = UUID.fromString("22222222-2222-4222-8222-222222222221");
    private static final UUID PRODUCT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void consolidatesDuplicatesAndSortsProducts() {
        var request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_B, 1),
                new CreateOrderRequest.Item(PRODUCT_A, 2),
                new CreateOrderRequest.Item(PRODUCT_A, 3)));

        var normalized = OrderCreationService.normalize(request);

        assertThat(normalized).containsExactly(
                new OrderCreationService.NormalizedItem(PRODUCT_A, 5),
                new OrderCreationService.NormalizedItem(PRODUCT_B, 1));
    }

    @Test
    void equivalentRequestsHaveTheSameFingerprint() {
        var first = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_A, 1),
                new CreateOrderRequest.Item(PRODUCT_B, 2),
                new CreateOrderRequest.Item(PRODUCT_A, 2)));
        var equivalent = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_A, 3),
                new CreateOrderRequest.Item(PRODUCT_B, 2)));

        assertThat(OrderCreationService.hash(OrderCreationService.normalize(first)))
                .isEqualTo(OrderCreationService.hash(OrderCreationService.normalize(equivalent)));
    }

    @Test
    void rejectsConsolidatedQuantityAboveTheBusinessLimit() {
        var request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(PRODUCT_A, 6000),
                new CreateOrderRequest.Item(PRODUCT_A, 5000)));

        assertThatThrownBy(() -> OrderCreationService.normalize(request))
                .isInstanceOf(InvalidOrderRequestException.class)
                .hasMessageContaining("10000");
    }
}
