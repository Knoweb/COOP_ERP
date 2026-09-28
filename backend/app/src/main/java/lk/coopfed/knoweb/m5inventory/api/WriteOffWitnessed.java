package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A write-off was witnessed, remotely when {@code remoteWitness}. */
public record WriteOffWitnessed(
        UUID writeOffId, UUID ownerEntityId, UUID locationId, UUID witnessUserId, boolean remoteWitness)
        implements DomainEvent {

    public static final String TYPE = "writeoff.witnessed.v1";
}
