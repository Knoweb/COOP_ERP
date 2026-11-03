package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when qualifying customer spend is recorded towards monthly reward calculation (PGL-M3-03).
 */
public record LoyaltySpendRecorded(
        UUID spendId,
        UUID ownerEntityId,
        UUID loyaltyId,
        UUID customerId,
        UUID locationId,
        LocalDate spendDate,
        BigDecimal amount,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "customers.loyalty_spend_recorded.v1";
}
