package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CreateDeliveryNote (24A section 6): the seller drafts a delivery note to one buyer, in drops
 * (a drop: the shop it goes to, the entity billed, the order lines it carries). Each line takes
 * no more than the seller allocated and has not yet dispatched of the order line.
 *
 * @param fromLocationId the seller's warehouse the goods leave from; optional, carried on the note
 *     (the kernel header's location) and on delivery_note.issued.v1 for M5's pick list
 */
public record CreateDeliveryNote(
        String vehicleRef, String driverName, String routeRef, List<Drop> drops, UUID fromLocationId) {

    public CreateDeliveryNote(String vehicleRef, String driverName, String routeRef, List<Drop> drops) {
        this(vehicleRef, driverName, routeRef, drops, null);
    }

    public record Drop(UUID shipToLocationId, UUID billToEntityId, List<Line> lines) {}

    /** @param batchId the batch keyed by the seller; optional (M5 picks otherwise) */
    public record Line(UUID orderLineId, BigDecimal qty, UUID batchId) {}
}
