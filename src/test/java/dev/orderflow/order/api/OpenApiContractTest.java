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
                .contains("Idempotency-Key")
                .contains("'201':")
                .contains("Location:")
                .contains("'400':")
                .contains("'401':")
                .contains("'403':")
                .contains("'409':")
                .contains("'422':")
                .contains("INVENTORY_PENDING")
                .contains("BRL");
    }
}
