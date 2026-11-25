package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when monthly qualifying spend, loyalty points reward and insurance eligibility are evaluated (PGL-M3-03).
 */
public record LoyaltyMonthlyRewardCalculated(
        UUID rewardId,
        UUID ownerEntityId,
        UUID loyaltyId,
        UUID customerId,
        String periodMonth,
        BigDecimal qualifyingSpend,
        int pointsAwarded,
        boolean insuranceEligible,
        String rewardStatus,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.loyalty_monthly_reward_calculated.v1";
}
