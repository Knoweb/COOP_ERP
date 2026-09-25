package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The credit limit of a relationship changed with an amendment (doc 21 flow 6.4); M4 recomputes
 * its exposure warning from it. The limit is informative (ADR-12): nothing is blocked by it.
 *
 * @param relationshipId         the new row, which carries the new limit
 * @param previousRelationshipId the row that carried the old limit
 * @param previousCreditLimit    null when there was none
 * @param creditLimit            the new limit
 * @param effectiveFrom          the first day of the new limit
 */
public record CreditLimitChanged(
        UUID relationshipId,
        UUID previousRelationshipId,
        BigDecimal previousCreditLimit,
        BigDecimal creditLimit,
        LocalDate effectiveFrom)
        implements DomainEvent {

    public static final String TYPE = "credit_limit.changed.v1";
}
