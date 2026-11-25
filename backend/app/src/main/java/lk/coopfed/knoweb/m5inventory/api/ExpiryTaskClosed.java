package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An expiry review task was closed (doc 25 section 4.6).
 */
public record ExpiryTaskClosed(UUID taskId, UUID ownerEntityId, UUID locationId, UUID batchId, String reason)
        implements DomainEvent {

    public static final String TYPE = "expiry_task.closed.v1";
}
