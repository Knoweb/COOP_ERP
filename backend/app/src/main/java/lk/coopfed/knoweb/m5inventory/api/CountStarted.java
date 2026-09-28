package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A count began; its expectation holds {@code lots} lots. */
public record CountStarted(UUID taskId, UUID ownerEntityId, UUID locationId, int lots) implements DomainEvent {

    public static final String TYPE = "count.started.v1";
}
