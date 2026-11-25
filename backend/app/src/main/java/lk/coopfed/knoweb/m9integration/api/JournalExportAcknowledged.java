package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** journal.acknowledged.v1: an accounting export was acknowledged. */
public record JournalExportAcknowledged(UUID exportId, UUID ownerEntityId, Instant acknowledgedAt)
        implements DomainEvent {

    public static final String TYPE = "journal.acknowledged.v1";
}
