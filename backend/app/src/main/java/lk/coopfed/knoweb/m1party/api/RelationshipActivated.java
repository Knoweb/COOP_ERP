package lk.coopfed.knoweb.m1party.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A DRAFT relationship became ACTIVE: from its first day the buyer may order (doc 21 flow 6.2). */
public record RelationshipActivated(
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String termsHash)
        implements DomainEvent {

    public static final String TYPE = "relationship.activated.v1";
}
