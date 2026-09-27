package dev.orderflow.order.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotEmpty @Size(max = 100) List<@Valid Item> items) {

    public record Item(
            @NotNull UUID productId,
            @Min(1) @Max(10000) int quantity) {
    }
}
