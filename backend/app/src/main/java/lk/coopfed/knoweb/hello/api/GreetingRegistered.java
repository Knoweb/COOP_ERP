package lk.coopfed.knoweb.hello.api;

import lk.coopfed.knoweb.kernel.api.DomainEvent;

import java.util.UUID;

/**
 * Published when a greeting has been registered, in the same transaction as the insert.
 *
 * <p>An event payload holds identifiers and changed facts only (17A section 4.4; doc 19
 * section 6.1): a consumer that needs the texts reads them through {@link GreetingQueries}.
 * Never put a name, phone number, NIC, PIN or token in an event.
 *
 * @param greetingId    the new greeting
 * @param ownerEntityId the entity that owns it
 */
public record GreetingRegistered(
        UUID greetingId,
        UUID ownerEntityId) implements DomainEvent {

    /** Dotted, versioned, never reused: a changed payload is a new version (.v2). */
    public static final String TYPE = "hello.greeting.registered.v1";
}
