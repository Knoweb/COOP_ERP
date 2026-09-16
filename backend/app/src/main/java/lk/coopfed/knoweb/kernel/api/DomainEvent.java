package lk.coopfed.knoweb.kernel.api;

/**
 * Marker interface for versioned domain events.
 *
 * Event implementations should expose a static TYPE constant,
 * e.g. hello.greeting.registered.v1.
 */
public interface DomainEvent {
}