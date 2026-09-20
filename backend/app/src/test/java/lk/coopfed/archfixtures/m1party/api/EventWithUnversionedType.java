package lk.coopfed.archfixtures.m1party.api;

import lk.coopfed.knoweb.kernel.api.DomainEvent;

import java.util.UUID;

/** Violates the event rule: the TYPE has no version, so its payload can never change safely. */
public record EventWithUnversionedType(UUID thingId) implements DomainEvent {

    public static final String TYPE = "party.thing.changed";
}
