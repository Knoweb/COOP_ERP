package lk.coopfed.knoweb.m9integration.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when a delivery trip is scheduled (M3-06).
 */
public record DeliveryTripCreated(
        UUID tripId,
        UUID ownerEntityId,
        String tripReference,
        String vehicleId,
        UUID originLocationId,
        LocalDate tripDate)
        implements DomainEvent {

    public static final String TYPE = "integration.delivery_trip_created.v1";
}
