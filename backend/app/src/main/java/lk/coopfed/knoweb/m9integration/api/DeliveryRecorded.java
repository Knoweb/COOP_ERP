package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when a delivery drop is recorded on a trip (M3-06).
 */
public record DeliveryRecorded(
        UUID deliveryId, UUID ownerEntityId, UUID tripId, String deliveryReference, UUID destinationLocationId)
        implements DomainEvent {

    public static final String TYPE = "integration.delivery_recorded.v1";
}
