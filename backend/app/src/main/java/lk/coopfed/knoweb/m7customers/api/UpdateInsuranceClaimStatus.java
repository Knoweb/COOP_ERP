package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * Updates the processing status of an insurance benefit claim (PGL-M3-03).
 */
public record UpdateInsuranceClaimStatus(UUID claimId, String status, String rejectionReason) {}
