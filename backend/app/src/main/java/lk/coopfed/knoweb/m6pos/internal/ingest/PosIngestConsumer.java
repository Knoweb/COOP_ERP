package lk.coopfed.knoweb.m6pos.internal.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The till's facts M6 applies (26A section 10, "ingest/ ReceiptBundleHook ... SessionHook"). The
 * sync gateway (K-08) accepts each event of a batch into the outbox with the device as its
 * source; the consumer framework delivers it once, in the OWN scope of the device's entity at the
 * device's shop, with the device on the scope. This class only reads the payload and hands the
 * command to a handler, which audits and publishes.
 *
 * <p>The receipt bundle is doc 32 section 3.1's: {@code payload.document} (doc 18's column
 * names, the fields the content hash covers), {@code payload.lines}, {@code payload.tenders}
 * and {@code payload.session_id}. The gateway has verified the content hash already.
 */
@Component
class PosIngestConsumer {

    static final String RECEIPT_ISSUED = "receipt.issued.v1";
    static final String SESSION_OPENED = "till_session.opened.v1";
    static final String SESSION_CLOSED = "till_session.closed.v1";

    private final Handles<RecordReceipt, UUID> receipts;
    private final Handles<RecordSession, UUID> sessions;

    PosIngestConsumer(Handles<RecordReceipt, UUID> receipts, Handles<RecordSession, UUID> sessions) {
        this.receipts = receipts;
        this.sessions = sessions;
    }

    @EventConsumer(types = RECEIPT_ISSUED, consumer = "m6.receipts")
    public void onReceiptIssued(JsonNode payload, ScopeContext scope) {
        JsonNode document = payload.path("document");
        List<RecordReceipt.Line> lines = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            lines.add(new RecordReceipt.Line(
                    line.path("line_no").asInt(),
                    uuid(line, "sku_id"),
                    uuid(line, "batch_id"),
                    text(line, "uom_code"),
                    decimal(line, "qty"),
                    decimal(line, "unit_price"),
                    decimal(line, "line_total")));
        }
        List<RecordReceipt.Tender> tenders = new ArrayList<>();
        for (JsonNode tender : payload.path("tenders")) {
            tenders.add(new RecordReceipt.Tender(
                    tender.path("seq").asInt(), text(tender, "kind"), decimal(tender, "amount")));
        }
        receipts.handle(
                new RecordReceipt(
                        uuid(document, "document_id"),
                        uuid(document, "location_id"),
                        uuid(document, "till_position_id"),
                        uuid(document, "device_id"),
                        uuid(payload, "session_id"),
                        uuid(document, "series_id"),
                        document.hasNonNull("doc_number")
                                ? document.path("doc_number").asLong()
                                : null,
                        text(document, "doc_number_display"),
                        instant(document, "issued_at"),
                        date(document, "business_date"),
                        uuid(document, "operator_user_id"),
                        text(document, "currency"),
                        decimal(document, "net_amount"),
                        decimal(document, "tax_amount"),
                        decimal(document, "gross_amount"),
                        text(payload, "content_hash"),
                        document.hasNonNull("device_seq")
                                ? document.path("device_seq").asLong()
                                : null,
                        lines,
                        tenders),
                scope);
    }

    @EventConsumer(types = SESSION_OPENED, consumer = "m6.sessions")
    public void onSessionOpened(JsonNode payload, ScopeContext scope) {
        sessions.handle(
                new RecordSession(
                        uuid(payload, "session_id"),
                        false,
                        uuid(payload, "till_position_id"),
                        uuid(payload, "operator_user_id"),
                        date(payload, "business_date"),
                        instant(payload, "opened_at"),
                        decimal(payload, "float_amount"),
                        null,
                        null,
                        null),
                scope);
    }

    @EventConsumer(types = SESSION_CLOSED, consumer = "m6.sessions")
    public void onSessionClosed(JsonNode payload, ScopeContext scope) {
        sessions.handle(
                new RecordSession(
                        uuid(payload, "session_id"),
                        true,
                        uuid(payload, "till_position_id"),
                        uuid(payload, "operator_user_id"),
                        date(payload, "business_date"),
                        instant(payload, "closed_at"),
                        null,
                        decimal(payload, "counted_cash"),
                        decimal(payload, "expected_cash"),
                        decimal(payload, "variance")),
                scope);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /** A decimal from its text, never through a double. */
    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }
}
