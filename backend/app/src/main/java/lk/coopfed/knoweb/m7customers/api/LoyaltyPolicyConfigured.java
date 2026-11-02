package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when an entity configures or updates its loyalty reward & insurance policy (PGL-M3-03).
 */
public record LoyaltyPolicyConfigured(
        UUID policyId,
        UUID ownerEntityId,
        String policyCode,
        BigDecimal monthlySpendThreshold,
        int minMembershipMonths,
        int monthlyRewardPoints,
        BigDecimal insuranceBenefitAmount,
        boolean active,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.loyalty_policy_configured.v1";
}
