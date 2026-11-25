package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when the status of a delivery drop changes (M3-06).
 */
public record DeliveryStatusUpdated(
        UUID deliveryId, UUID ownerEntityId, UUID tripId, String deliveryReference, String status, Instant updatedAt)
        implements DomainEvent {

    public static final String TYPE = "integration.delivery_status_updated.v1";
}
