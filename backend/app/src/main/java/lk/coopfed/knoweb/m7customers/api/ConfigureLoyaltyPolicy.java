package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;

/**
 * Configures or updates the entity's loyalty reward and insurance policy (PGL-M3-03).
 */
public record ConfigureLoyaltyPolicy(
        String policyName,
        BigDecimal monthlySpendThreshold,
        Integer minMembershipMonths,
        Integer monthlyRewardPoints,
        BigDecimal insuranceBenefitAmount,
        Boolean active) {}
