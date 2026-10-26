package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A shelf-label PDF was rendered; {@code count} excludes labels refused above a control price. */
public record LabelJobRendered(UUID jobId, UUID locationId, int count) implements DomainEvent {

    public static final String TYPE = "label_job.rendered.v1";
}
