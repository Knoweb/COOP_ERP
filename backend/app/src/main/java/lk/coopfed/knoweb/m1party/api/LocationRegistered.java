package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A location exists, in PLANNED status (doc 21 section 5.3). The location is the aggregate the
 * event is about; its component is named registeredLocationId because the outbox reads a
 * component called locationId as the place an event happened, not its subject (the kernel's
 * LocationDayClosed does the same). Names and address stay out of the payload: the event
 * carries ids and codes, and a consumer that needs more reads the location.
 */
public record LocationRegistered(
        UUID registeredLocationId,
        UUID ownerEntityId,
        String locationCode,
        String locationType,
        String language,
        String status)
        implements DomainEvent {

    public static final String TYPE = "location.registered.v1";
}
