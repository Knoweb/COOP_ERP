package lk.coopfed.knoweb.kernel.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * All open sessions for a location and business date have closed (doc 26 section 3.5,
 * 19A section 12, wave 2 TWK-23, C-02d). Published by M6 POS after verifying against
 * central session records; consumed by DayCloseTrigger to advance the location's business date.
 *
 * <p>The location is the aggregate the event is about, named closedLocationId because the outbox
 * reads a locationId component as the place of an event, not its subject.
 */
public record LocationSessionsClosed(UUID closedLocationId, LocalDate businessDate, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "location.sessions_closed.v1";
}
