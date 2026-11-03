package lk.coopfed.knoweb.m7customers.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a customer's regional registration details or branch access are updated (PGL-M3-01).
 * Never carries PII.
 */
public record RegionalRegistrationUpdated(
        UUID loyaltyId,
        UUID customerId,
        UUID ownerEntityId,
        UUID registeredLocationId,
        String regionCode,
        String loyaltyTier,
        String status,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.regional_registration_updated.v1";
}
