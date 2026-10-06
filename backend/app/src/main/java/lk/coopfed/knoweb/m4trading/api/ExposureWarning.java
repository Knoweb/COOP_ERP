package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * exposure.warning.v1 (doc 24 section 3.8; 24A section 6.3): accepting {@code orderId} took the
 * buyer's exposure with the seller across {@code thresholdPercent} of the relationship's credit
 * limit. A warning only: nothing is blocked on credit (ADR-12).
 */
public record ExposureWarning(
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal amount,
        BigDecimal creditLimit,
        int thresholdPercent,
        UUID orderId)
        implements DomainEvent {

    public static final String TYPE = "exposure.warning.v1";
}
