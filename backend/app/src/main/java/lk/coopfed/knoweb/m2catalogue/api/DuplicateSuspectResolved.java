package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a duplicate SKU suspect is resolved (MERGED or DISMISSED).
 */
public record DuplicateSuspectResolved(UUID suspectId, String decision, UUID decidedBy, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "duplicate.suspect_resolved.v1";
}
