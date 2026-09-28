package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A write-off was drafted. */
public record WriteOffRequested(UUID writeOffId, UUID ownerEntityId, UUID locationId, String category, int lines)
        implements DomainEvent {

    public static final String TYPE = "writeoff.requested.v1";
}
