package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only view of a submitted loyalty insurance claim (PGL-M3-03).
 */
public record LoyaltyInsuranceClaimView(
        UUID claimId,
        UUID ownerEntityId,
        UUID loyaltyId,
        UUID customerId,
        String claimType,
        String claimStatus,
        BigDecimal policyBenefitAmount,
        LocalDate incidentDate,
        String claimantRelationship,
        String supportingDocumentRef,
        boolean eligibilityVerified,
        String eligibilitySnapshotJson,
        String rejectionReason,
        Instant submittedAt,
        Instant updatedAt) {}
