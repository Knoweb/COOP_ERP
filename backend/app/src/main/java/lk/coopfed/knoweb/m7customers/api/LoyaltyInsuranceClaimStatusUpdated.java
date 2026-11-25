package lk.coopfed.knoweb.m7customers.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when an insurance benefit claim's lifecycle status is updated (PGL-M3-03).
 */
public record LoyaltyInsuranceClaimStatusUpdated(
        UUID claimId, UUID ownerEntityId, UUID loyaltyId, String previousStatus, String newStatus, Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.loyalty_insurance_claim_status_updated.v1";
}
