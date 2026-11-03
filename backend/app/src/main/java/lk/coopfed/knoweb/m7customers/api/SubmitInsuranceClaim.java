package lk.coopfed.knoweb.m7customers.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Submits an insurance benefit claim (e.g. death benefit) with supporting document references (PGL-M3-03).
 * Verifies eligibility against historical qualifying records without automatic cash payout.
 */
public record SubmitInsuranceClaim(
        UUID loyaltyId,
        String claimType,
        LocalDate incidentDate,
        String claimantRelationship,
        String supportingDocumentRef) {}
