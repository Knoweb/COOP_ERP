package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * GrnConsumer (25A section 4, "consumers/"; section 6.2): {@code grn.confirmed.v1} puts the goods
 * into the receiver's lots. The consumer framework delivers the event once, in the OWN scope of its
 * owner, the receiver, and in a transaction; this class only reads the payload and hands the
 * command to {@link ApplyGrnReceiptHandler}, which audits and publishes.
 *
 * <p>The payload is M4's {@code GrnConfirmed} record (grnId, receiverEntityId,
 * receiverLocationId, confirmedAt, lines of lineId, batchId, receivedQty, damagedQty, unitCost),
 * read as JSON: M5 imports nothing of M4.
 */
@Component
class GrnConsumer {

    static final String CONSUMER = "m5.receipts";
    static final String GRN_CONFIRMED = "grn.confirmed.v1";

    private final Handles<ApplyGrnReceipt, Integer> receipt;

    GrnConsumer(Handles<ApplyGrnReceipt, Integer> receipt) {
        this.receipt = receipt;
    }

    @EventConsumer(types = GRN_CONFIRMED, consumer = CONSUMER)
    public void onGrnConfirmed(JsonNode payload, ScopeContext scope) {
        List<ApplyGrnReceipt.Line> lines = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            lines.add(new ApplyGrnReceipt.Line(
                    Payloads.uuid(line, "lineId"),
                    Payloads.uuid(line, "batchId"),
                    Payloads.decimal(line, "receivedQty"),
                    Payloads.decimal(line, "damagedQty"),
                    Payloads.decimal(line, "unitCost")));
        }
        receipt.handle(
                new ApplyGrnReceipt(
                        Payloads.uuid(payload, "grnId"),
                        Payloads.uuid(payload, "receiverEntityId"),
                        Payloads.uuid(payload, "receiverLocationId"),
                        Payloads.instant(payload, "confirmedAt"),
                        lines),
                scope);
    }
}
