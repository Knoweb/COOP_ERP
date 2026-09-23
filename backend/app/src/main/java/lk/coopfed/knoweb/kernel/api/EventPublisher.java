package lk.coopfed.knoweb.kernel.api;

public interface EventPublisher {

    void publish(DomainEvent event);
}
