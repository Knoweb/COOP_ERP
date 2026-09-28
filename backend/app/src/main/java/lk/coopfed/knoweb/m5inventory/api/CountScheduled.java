package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A count of a location was scheduled. */
public record CountScheduled(UUID taskId, UUID ownerEntityId, UUID locationId, String scopeKind)
        implements DomainEvent {

    public static final String TYPE = "count.scheduled.v1";
}
