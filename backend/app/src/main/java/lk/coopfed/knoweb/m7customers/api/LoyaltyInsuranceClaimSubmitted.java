package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when an insurance benefit claim (e.g. death benefit) is submitted and eligibility is verified (PGL-M3-03).
 */
public record LoyaltyInsuranceClaimSubmitted(
        UUID claimId,
        UUID ownerEntityId,
        UUID loyaltyId,
        UUID customerId,
        String claimType,
        BigDecimal policyBenefitAmount,
        LocalDate incidentDate,
        boolean eligibilityVerified,
        String claimStatus,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.loyalty_insurance_claim_submitted.v1";
}
