package dev.orderflow.order.outbox;

public interface OutboxSender {
    void send(OutboxMessage message) throws Exception;
}
