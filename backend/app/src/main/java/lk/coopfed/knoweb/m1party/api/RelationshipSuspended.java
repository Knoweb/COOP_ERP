package lk.coopfed.knoweb.m1party.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** An ACTIVE relationship was suspended; documents already open under it are unaffected. */
public record RelationshipSuspended(
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String termsHash)
        implements DomainEvent {

    public static final String TYPE = "relationship.suspended.v1";
}
