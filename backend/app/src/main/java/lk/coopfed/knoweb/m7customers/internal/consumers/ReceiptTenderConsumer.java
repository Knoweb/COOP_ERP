package lk.coopfed.knoweb.m7customers.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import org.springframework.stereotype.Component;

/**
 * The receipt tender consumer (27A section 6.2, {@code m7.tenders}): every ACCOUNT tender of a
 * receipt a till issued becomes a CHARGE on the customer's account; of a refund or a void, a
 * CREDIT. The sync gateway accepted the till's event into the outbox; the consumer framework
 * delivers it once, in the OWN scope of the device's society at its shop, as it does to M5's
 * {@code m5.sales} and M6's {@code m6.receipts}. This class only reads the payload (doc 32
 * section 3.1: {@code payload.document}, {@code payload.tenders}) and hands each tender to
 * {@link PostAccountTenderHandler}, which audits and publishes.
 *
 * <p>An ACCOUNT tender carries {@code customer_account_id} and {@code offline} beside {@code seq},
 * {@code kind} and {@code amount} (27A section 2, "doc_receipt_tender rows (kind ACCOUNT,
 * customer_account_id, offline flag)"); the till track writes them (CR-30-1, the till is later).
 */
@Component
class ReceiptTenderConsumer {

    static final String RECEIPT_ISSUED = "receipt.issued.v1";
    static final String RECEIPT_REFUNDED = "receipt.refunded.v1";
    static final String RECEIPT_VOIDED = "receipt.voided.v1";
    static final String ACCOUNT = "ACCOUNT";

    private final Handles<PostAccountTender, UUID> post;

    ReceiptTenderConsumer(Handles<PostAccountTender, UUID> post) {
        this.post = post;
    }

    @EventConsumer(types = RECEIPT_ISSUED, consumer = "m7.tenders")
    public void onIssued(JsonNode payload, ScopeContext scope) {
        post(PostAccountTender.CHARGE, payload, scope);
    }

    @EventConsumer(
            types = {RECEIPT_REFUNDED, RECEIPT_VOIDED},
            consumer = "m7.tenders")
    public void onRefundedOrVoided(JsonNode payload, ScopeContext scope) {
        post(PostAccountTender.CREDIT, payload, scope);
    }

    /** Hands each ACCOUNT tender of the bundle to the handler; the other tenders are M6's business. */
    void post(String kind, JsonNode payload, ScopeContext scope) {
        JsonNode document = payload.path("document");
        for (JsonNode tender : payload.path("tenders")) {
            if (!ACCOUNT.equals(text(tender, "kind"))) {
                continue;
            }
            post.handle(
                    new PostAccountTender(
                            kind,
                            uuid(tender, "customer_account_id"),
                            decimal(tender, "amount"),
                            uuid(document, "document_id"),
                            text(document, "doc_number_display"),
                            tender.path("seq").asInt(),
                            uuid(document, "location_id"),
                            date(document, "business_date"),
                            uuid(document, "operator_user_id"),
                            tender.path("offline").asBoolean(false)),
                    scope);
        }
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

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }
}
