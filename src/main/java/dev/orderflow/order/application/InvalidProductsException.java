package dev.orderflow.order.application;

import java.util.List;
import java.util.UUID;

public class InvalidProductsException extends RuntimeException {
    private final List<UUID> productIds;

    public InvalidProductsException(List<UUID> productIds) {
        super("One or more products do not exist or are inactive");
        this.productIds = List.copyOf(productIds);
    }

    public List<UUID> productIds() {
        return productIds;
    }
}
