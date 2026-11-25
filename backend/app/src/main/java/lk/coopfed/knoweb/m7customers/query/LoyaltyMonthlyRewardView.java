package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of a customer's monthly loyalty reward and insurance eligibility evaluation (PGL-M3-03).
 */
public record LoyaltyMonthlyRewardView(
        UUID rewardId,
        UUID ownerEntityId,
        UUID loyaltyId,
        UUID customerId,
        String periodMonth,
        BigDecimal qualifyingSpend,
        BigDecimal spendThreshold,
        int membershipMonths,
        int minMembershipMonths,
        int pointsAwarded,
        boolean insuranceEligible,
        String rewardStatus,
        String disqualificationReason,
        Instant evaluatedAt) {}
