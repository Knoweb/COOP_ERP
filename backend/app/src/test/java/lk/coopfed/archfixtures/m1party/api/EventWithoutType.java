package lk.coopfed.archfixtures.m1party.api;

import lk.coopfed.knoweb.kernel.api.DomainEvent;

import java.util.UUID;

/** Violates the event rule: no TYPE constant, so the outbox cannot name what happened. */
public record EventWithoutType(UUID thingId) implements DomainEvent {
}
