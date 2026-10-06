package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
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
 * sent as far as it goes (the request's line is short), and a request with nothing left to send
 * fails ({@code m5.transfer.insufficient_stock}) and waits in the dead letter queue for a person.
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
    private final InventoryQueries inventory;

    TransferRequestConsumer(Handles<IssueTransfer, UUID> issue, InventoryQueries inventory) {
        this.issue = issue;
        this.inventory = inventory;
    }

    @EventConsumer(types = APPROVED, consumer = CONSUMER)
    public void onApproved(JsonNode payload, ScopeContext scope) {
        UUID from = Payloads.uuid(payload, "fromLocationId");
        List<IssueTransfer.Line> lines = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            BigDecimal wanted = Payloads.decimal(line, "qty");
            UUID sku = Payloads.uuid(line, "skuId");
            if (wanted == null || sku == null) {
                continue;
            }
            for (LotBalance lot : inventory.pickBatches(from, sku, scope)) {
                if (wanted.signum() <= 0) {
                    break;
                }
                if (!"GOOD".equals(lot.condition()) || lot.qtyOnHand().signum() <= 0) {
                    continue;
                }
                BigDecimal take = wanted.min(lot.qtyOnHand());
                lines.add(new IssueTransfer.Line(lot.batchId(), take));
                wanted = wanted.subtract(take);
            }
        }
        if (lines.isEmpty()) {
            throw new ProblemException("m5.transfer.insufficient_stock");
        }
        issue.handle(
                new IssueTransfer(
                        from, Payloads.uuid(payload, "toLocationId"), lines, Payloads.uuid(payload, "requestId")),
                scope);
    }
}
