package lk.coopfed.knoweb.kernel.internal.event;

public interface BrokerAdapter {

    void publish(OutboxMessage message);
}
