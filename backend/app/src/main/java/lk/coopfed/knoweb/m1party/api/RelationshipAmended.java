package lk.coopfed.knoweb.m1party.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The terms of a relationship were amended from {@code effectiveFrom}: the row
 * {@code previousRelationshipId} now ends the day before, and the new ACTIVE row
 * {@code relationshipId} carries the new terms (21A section 6.1).
 */
public record RelationshipAmended(
        UUID relationshipId,
        UUID previousRelationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String termsHash)
        implements DomainEvent {

    public static final String TYPE = "relationship.amended.v1";
}
