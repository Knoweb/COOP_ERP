package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * ReceiptConsumer, the sales half (25A section 4, "consumers/ ReceiptConsumer (sale
 * deductions)"; section 6.2; section 7.4: "sale deduction: not a separate bundle - M6's receipt
 * bundle lines carry batchId; m5.sales consumes receipt.issued.v1"). The sync gateway accepted
 * the till's bundle into the outbox with the device as its source; the consumer framework
 * delivers it once, in the OWN scope of the device's entity at its shop, with the device on the
 * scope, so the movements are numbered in the device's own sequence.
 *
 * <p>The payload is the till's bundle (doc 32 section 3.1): {@code document} and {@code lines}
 * with doc 18's column names. M5 imports nothing of M6.
 */
@Component
class SaleConsumer {

    static final String CONSUMER = "m5.sales";
    static final String RECEIPT_ISSUED = "receipt.issued.v1";

    private final Handles<ApplySale, Integer> sale;

    SaleConsumer(Handles<ApplySale, Integer> sale) {
        this.sale = sale;
    }

    @EventConsumer(types = RECEIPT_ISSUED, consumer = CONSUMER)
    public void onReceiptIssued(JsonNode payload, ScopeContext scope) {
        JsonNode document = payload.path("document");
        List<ApplySale.Line> lines = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            lines.add(new ApplySale.Line(
                    Payloads.uuid(line, "line_id"),
                    line.path("line_no").asInt(),
                    Payloads.uuid(line, "sku_id"),
                    Payloads.uuid(line, "batch_id"),
                    Payloads.decimal(line, "qty")));
        }
        String issued = document.path("issued_at").asText(null);
        sale.handle(
                new ApplySale(
                        Payloads.uuid(document, "document_id"),
                        Payloads.uuid(document, "location_id"),
                        issued == null ? null : Instant.parse(issued),
                        lines),
                scope);
    }
}
