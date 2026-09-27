package lk.coopfed.knoweb.m4trading.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.api.DeliveryLineSummary;

/**
 * A delivery note as both parties see it (doc 24 section 5.2). The status is the document's
 * (DRAFT, ISSUED, IN_TRANSIT), or CLOSED once every drop is RECEIVED; a drop is RECEIVED when an
 * issued GRN of the receiver names it (CR-24A-1 item 3), PLANNED otherwise.
 */
public record DeliveryView(
        UUID deliveryNoteId,
        String docNumberDisplay,
        String status,
        UUID sellerEntityId,
        UUID buyerEntityId,
        UUID fromLocationId,
        String vehicleRef,
        String driverName,
        Instant dispatchedAt,
        Instant issuedAt,
        List<DropView> drops) {

    public record DropView(
            UUID dropId,
            int seq,
            UUID shipToLocationId,
            UUID billToEntityId,
            List<UUID> orderIds,
            String status,
            UUID grnId,
            List<DeliveryLineSummary> lines) {}
}
