package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of a configured loyalty reward and insurance policy (PGL-M3-03).
 */
public record LoyaltyRewardPolicyView(
        UUID policyId,
        UUID ownerEntityId,
        String policyName,
        BigDecimal monthlySpendThreshold,
        int minMembershipMonths,
        int monthlyRewardPoints,
        BigDecimal insuranceBenefitAmount,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
