package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A queued shelf-label job reached a terminal render failure (CR-23A-2). */
public record LabelJobFailed(UUID jobId, UUID locationId) implements DomainEvent {

    public static final String TYPE = "label_job.failed.v1";
}
