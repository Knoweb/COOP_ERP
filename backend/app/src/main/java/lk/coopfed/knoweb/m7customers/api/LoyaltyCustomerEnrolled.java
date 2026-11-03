package lk.coopfed.knoweb.m7customers.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a customer is enrolled into centralized loyalty with regional registration (PGL-M3-01).
 * Never carries PII (no name, phone, or NIC).
 */
public record LoyaltyCustomerEnrolled(
        UUID loyaltyId,
        UUID customerId,
        UUID ownerEntityId,
        UUID registeredLocationId,
        String regionCode,
        String loyaltyTier,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.loyalty_customer_enrolled.v1";
}
