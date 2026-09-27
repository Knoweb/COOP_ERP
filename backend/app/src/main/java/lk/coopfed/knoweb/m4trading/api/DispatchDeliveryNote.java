package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * DispatchDeliveryNote (24A section 6, Dispatch): the vehicle leaves with an issued note. The
 * vehicle and the driver are named here when the draft did not name them.
 */
public record DispatchDeliveryNote(UUID deliveryNoteId, String vehicleRef, UUID driverUserId, String driverName) {}
