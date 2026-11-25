package lk.coopfed.knoweb.kernel.internal.event;

/**
 * The NATS side of the broker boundary in 19A K-05.
 *
 * <p>This is deliberately a compile-time stub: the platform currently runs RabbitMQ, so this
 * class has no NATS client dependency and is not a Spring component. If the broker decision
 * changes, a NATS implementation replaces the refusal behind {@link BrokerAdapter} without
 * changing the outbox relay or its callers.
 */
public final class NatsBrokerAdapter implements BrokerAdapter {

    static final String NOT_IMPLEMENTED = "NATS broker support is not implemented; RabbitMQ remains the active broker";

    @Override
    public void publish(OutboxMessage message) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
