package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * DeliveryConsumer (25A sections 4 and 6.2): {@code delivery_note.issued.v1} reserves the seller's
 * lots on a pick list, {@code delivery_note.dispatched.v1} moves them out when the vehicle leaves.
 * Delivered in the OWN scope of the event's owner, the seller.
 *
 * <p>The payloads are M4's {@code DeliveryNoteIssued} (deliveryNoteId, sellerEntityId, drops of
 * lines: lineId, skuId, batchId, dispatchedQty) and {@code DeliveryNoteDispatched}
 * (deliveryNoteId, sellerEntityId, dispatchedAt), read as JSON. A line's quantity is taken in the
 * item's base unit: the demo delivers in base units, and the conversion (M2) is deferred.
 */
@Component
class DeliveryConsumer {

    static final String CONSUMER = "m5.deliveries";
    static final String ISSUED = "delivery_note.issued.v1";
    static final String DISPATCHED = "delivery_note.dispatched.v1";

    private final Handles<ReserveDelivery, UUID> reserve;
    private final Handles<DispatchDelivery, Integer> dispatch;

    DeliveryConsumer(Handles<ReserveDelivery, UUID> reserve, Handles<DispatchDelivery, Integer> dispatch) {
        this.reserve = reserve;
        this.dispatch = dispatch;
    }

    @EventConsumer(types = ISSUED, consumer = CONSUMER)
    public void onIssued(JsonNode payload, ScopeContext scope) {
        List<ReserveDelivery.Line> lines = new ArrayList<>();
        for (JsonNode drop : payload.path("drops")) {
            for (JsonNode line : drop.path("lines")) {
                lines.add(new ReserveDelivery.Line(
                        Payloads.uuid(line, "lineId"),
                        Payloads.uuid(line, "skuId"),
                        Payloads.uuid(line, "batchId"),
                        Payloads.decimal(line, "dispatchedQty")));
            }
        }
        reserve.handle(
                new ReserveDelivery(
                        Payloads.uuid(payload, "deliveryNoteId"), Payloads.uuid(payload, "sellerEntityId"), lines),
                scope);
    }

    @EventConsumer(types = DISPATCHED, consumer = CONSUMER)
    public void onDispatched(JsonNode payload, ScopeContext scope) {
        dispatch.handle(
                new DispatchDelivery(
                        Payloads.uuid(payload, "deliveryNoteId"),
                        Payloads.uuid(payload, "sellerEntityId"),
                        Payloads.instant(payload, "dispatchedAt")),
                scope);
    }
}
