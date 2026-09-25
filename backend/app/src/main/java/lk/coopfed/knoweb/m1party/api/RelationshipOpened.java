package lk.coopfed.knoweb.m1party.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A relationship was opened in DRAFT. Payload as doc 21 section 5.3 names it for the family:
 * the relationship, the parties, the effective range and a hash of the terms (a consumer that
 * needs the terms reads them through {@code RelationshipQueries}).
 */
public record RelationshipOpened(
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String termsHash)
        implements DomainEvent {

    public static final String TYPE = "relationship.opened.v1";
}
