package lk.coopfed.knoweb.m6pos.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Central recorded a cash movement (drop or pay-out) from a till.
 */
public record CashMovementRecorded(
        UUID movementId, UUID sessionId, UUID ownerEntityId, UUID locationId, String kind, BigDecimal amount)
        implements DomainEvent {

    public static final String TYPE = "till_cash_movement.recorded.v1";
}
