package lk.coopfed.knoweb.m1party.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The relationship queries of doc 21 section 5.2 and 21A section 7, published for the modules
 * that trade under a relationship: M3 binds a TRADE list to it (23A section 2), M4 reads its
 * terms to accept an order, lock it and warn on exposure (24A section 2), M2 and M5 look it up.
 *
 * <p>Row-level security decides what a caller sees: the seller and the buyer each see their
 * relationships (in OWN or PARTY scope), the Federation view sees all, a regulator those of its
 * grant, anybody else nothing. No method filters by tenant itself.
 */
public interface RelationshipQueries {

    /**
     * LookupRelationship(seller, buyer, date): the ACTIVE row of this pair whose effective range
     * contains the date, with its terms. Empty when there is none, or when the caller may not
     * see it. At most one row can match (the A-I3 exclusion constraint).
     */
    Optional<RelationshipView> lookupRelationship(
            UUID sellerEntityId, UUID buyerEntityId, LocalDate onDate, ScopeContext scope);

    /** One row by its id; empty when it does not exist or the caller may not see it. */
    Optional<RelationshipView> getRelationship(UUID relationshipId, ScopeContext scope);

    /**
     * ListRelationships(entity, role): every row, of every status, in which the caller's scope
     * entity is on the given side, or on either side when {@code side} is null; ordered by
     * counterparty and then by effective date, so a pair's history reads in order.
     */
    List<RelationshipView> listRelationships(RelationshipSide side, ScopeContext scope);
}
