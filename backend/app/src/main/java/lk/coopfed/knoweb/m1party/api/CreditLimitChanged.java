package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The credit limit of a relationship changed (doc 21 flow 6.4): with an amendment, with the
 * activation of a draft that carries a limit (null to the opening limit), or once for a
 * relationship activated before activation published it (the backfill's announcement; wave 2,
 * CR-21A-7 and the M8 decision D6). M4 recomputes its exposure warning from it and M8 projects
 * the current limit of each pair from it. The limit is informative (ADR-12): nothing is blocked
 * by it.
 *
 * @param relationshipId         the row that carries the new limit
 * @param previousRelationshipId the row that carried the old limit; null at the activation of the
 *                               pair's first row and in an announcement (the activation of a row
 *                               that succeeds another names that row; wave 3, M1M2M3M5-04)
 * @param previousCreditLimit    null when there was none
 * @param creditLimit            the new limit; null when a successor row carries none
 * @param effectiveFrom          the first day of the new limit
 * @param sellerEntityId         the seller, whose relationship it is (added in wave 2, additive)
 * @param buyerEntityId          the buyer the limit is extended to (added in wave 2, additive)
 */
public record CreditLimitChanged(
        UUID relationshipId,
        UUID previousRelationshipId,
        BigDecimal previousCreditLimit,
        BigDecimal creditLimit,
        LocalDate effectiveFrom,
        UUID sellerEntityId,
        UUID buyerEntityId)
        implements DomainEvent {

    public static final String TYPE = "credit_limit.changed.v1";
}
