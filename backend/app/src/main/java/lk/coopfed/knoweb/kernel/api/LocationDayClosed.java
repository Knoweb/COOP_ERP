package lk.coopfed.knoweb.kernel.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A location closed its business day (19A section 12): {@code closedDate} is over,
 * {@code businessDate} is what it trades on now. End-of-day aggregation (M8) and
 * {@code apply_from} activation (doc 32 section 5.2) consume it. The location is the
 * aggregate the event is about, named closedLocationId because the outbox reads a
 * locationId component as the place of an event, not its subject.
 */
public record LocationDayClosed(UUID closedLocationId, LocalDate closedDate, LocalDate businessDate, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "location.day_closed.v1";
}
