package dev.orderflow.order.application;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException() {
        super("Order was not found");
    }
}
