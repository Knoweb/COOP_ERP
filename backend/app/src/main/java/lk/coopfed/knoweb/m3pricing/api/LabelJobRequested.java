package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A shelf-label job was queued. The scheduled worker discovers the row from pricing.label_job. */
public record LabelJobRequested(UUID jobId, UUID locationId, int requestedCount) implements DomainEvent {

    public static final String TYPE = "label_job.requested.v1";
}
