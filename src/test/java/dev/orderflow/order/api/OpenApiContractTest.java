package dev.orderflow.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class OpenApiContractTest {

    @Test
    void documentsTheVersionedCreationContractAndErrors() throws Exception {
        String contract = Files.readString(Path.of("docs", "openapi.yaml"));

        assertThat(contract)
                .contains("/api/v1/orders:")
                .contains("/api/v1/orders/{orderId}:")
                .contains("/api/v1/orders/{orderId}/history:")
                .contains("Idempotency-Key")
                .contains("'201':")
                .contains("Location:")
                .contains("'400':")
                .contains("'401':")
                .contains("'403':")
                .contains("'409':")
                .contains("'422':")
                .contains("OrderDetails")
                .contains("OrderHistory")
                .contains("Order does not exist or is not visible")
                .contains("INVENTORY_PENDING")
                .contains("BRL");
    }
}
