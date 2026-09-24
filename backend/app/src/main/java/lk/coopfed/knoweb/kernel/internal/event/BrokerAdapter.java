package lk.coopfed.knoweb.kernel.internal.event;

@FunctionalInterface
public interface BrokerAdapter {

    void publish(OutboxMessage message);

    default void publishToConsumer(String consumer, OutboxMessage message) {

        publishToConsumer(consumer, message, 1);
    }

    default void publishToConsumer(String consumer, OutboxMessage message, int attempt) {

        publish(message);
    }

    default void deadLetter(String queue, String consumer, OutboxMessage message, int attempts, String error) {

        publishToConsumer(queue, message, attempts);
    }
}
