package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import org.springframework.stereotype.Component;

/**
 * 25A section 6.2, "transfer_request.approved.v1: IssueTransfer(from, to, lines) then publish
 * transfer.issued.v1": the society approved a shop's request (M4-10), so the stores send the goods.
 * The request names items; this picks the batches first-expiry-first from the source's GOOD lots
 * ({@link InventoryQueries#pickBatches}) and hands {@link IssueTransfer}, naming the request, to
 * the issue handler, which audits, publishes and refuses a second transfer for the same request.
 *
 * <p>The approval checked the availability; stock sold between the approval and this delivery is
 * sent as far as it goes, and a request with nothing left to send issues no transfer and is
 * flagged {@code TRANSFER_REQUEST_SHORT} with event {@code transfer_request.unfilled.v1}
 * ({@link RecordTransferRequestUnfilledHandler}; wave 3, M1M2M3M5-17), where it used to fail and
 * wait unseen in the dead letter queue. Since wave 2
 * (M5-03; {@code 2026-10-06-wave2-stock-movements.md} (2)) a request is filled once: each item sent
 * short or not at all is handed to the issue as a shortfall (wanted, sent), which the handler
 * audits as {@code TRANSFER_REQUEST_SHORT} (REVIEW) and counts in {@code transfer.issued.v1}'s
 * {@code shortLines}; the shop raises a new request for the rest. Expired lots are never sent.
 *
 * <p>The payload is M4's {@code TransferRequestApproved} record (requestId, ownerEntityId,
 * fromLocationId, toLocationId, lines of skuId and qty), read as JSON: M5 imports nothing of M4.
 * Delivered in the society's OWN scope, entity-wide.
 */
@Component
class TransferRequestConsumer {

    static final String CONSUMER = "m5.transfer-requests";
    static final String APPROVED = "transfer_request.approved.v1";

    private final Handles<IssueTransfer, UUID> issue;
    private final Handles<RecordTransferRequestUnfilled, Void> unfilled;
    private final InventoryQueries inventory;

    TransferRequestConsumer(
            Handles<IssueTransfer, UUID> issue,
            Handles<RecordTransferRequestUnfilled, Void> unfilled,
            InventoryQueries inventory) {
        this.issue = issue;
        this.unfilled = unfilled;
        this.inventory = inventory;
    }

    @EventConsumer(types = APPROVED, consumer = CONSUMER)
    public void onApproved(JsonNode payload, ScopeContext scope) {
        UUID from = Payloads.uuid(payload, "fromLocationId");
        List<IssueTransfer.Line> lines = new ArrayList<>();
        List<IssueTransfer.Shortfall> shortfalls = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            BigDecimal wanted = Payloads.decimal(line, "qty");
            UUID sku = Payloads.uuid(line, "skuId");
            if (wanted == null || sku == null) {
                continue;
            }
            BigDecimal remaining = wanted;
            // In-date lots only, each with what no delivery note's pick list holds (wave 2, M5-01).
            for (LotBalance lot : inventory.pickBatches(from, sku, scope)) {
                if (remaining.signum() <= 0) {
                    break;
                }
                if (!"GOOD".equals(lot.condition()) || lot.qtyOnHand().signum() <= 0) {
                    continue;
                }
                BigDecimal take = remaining.min(lot.qtyOnHand());
                lines.add(new IssueTransfer.Line(lot.batchId(), take));
                remaining = remaining.subtract(take);
            }
            if (remaining.signum() > 0) {
                shortfalls.add(new IssueTransfer.Shortfall(sku, wanted, wanted.subtract(remaining)));
            }
        }
        if (lines.isEmpty()) {
            // wave 3, M1M2M3M5-17: nothing to send is flagged for a person, not thrown into the
            // dead letter queue where nobody is told.
            unfilled.handle(
                    new RecordTransferRequestUnfilled(
                            Payloads.uuid(payload, "requestId"),
                            from,
                            Payloads.uuid(payload, "toLocationId"),
                            shortfalls),
                    scope);
            return;
        }
        issue.handle(
                new IssueTransfer(
                        from,
                        Payloads.uuid(payload, "toLocationId"),
                        lines,
                        Payloads.uuid(payload, "requestId"),
                        shortfalls),
                scope);
    }
}
