package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A write-off was rejected; no stock moved. */
public record WriteOffRejected(UUID writeOffId, UUID ownerEntityId, UUID locationId) implements DomainEvent {

    public static final String TYPE = "writeoff.rejected.v1";
}
