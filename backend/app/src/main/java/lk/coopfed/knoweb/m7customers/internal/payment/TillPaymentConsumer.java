package lk.coopfed.knoweb.m7customers.internal.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The till's repayments (27A section 7.3, the CPR bundle; consumer {@code m7.repayments}). The sync
 * gateway accepted the till's {@code customer_payment.issued.v1} into the outbox with the device as
 * its source; the consumer framework delivers it once, in the OWN scope of the device's society at
 * its shop. This class only reads the payload and hands it to {@link RecordTillPaymentHandler},
 * which audits and publishes.
 *
 * <p>The payload, as the till track writes it (CR-30-1): {@code document} (doc 18's column names:
 * {@code document_id}, {@code series_id}, {@code doc_number}, {@code doc_number_display},
 * {@code location_id}, {@code till_position_id}, {@code device_id}, {@code business_date},
 * {@code operator_user_id}) and {@code payment} ({@code customer_account_id}, {@code method},
 * {@code amount} as text, {@code reference}, {@code allocation_mode} OLDEST_FIRST). No name, phone
 * or NIC travels: the gateway refuses a payload that carries one.
 */
@Component
class TillPaymentConsumer {

    static final String CUSTOMER_PAYMENT_ISSUED = "customer_payment.issued.v1";

    private final Handles<RecordTillPayment, UUID> record;

    TillPaymentConsumer(Handles<RecordTillPayment, UUID> record) {
        this.record = record;
    }

    @EventConsumer(types = CUSTOMER_PAYMENT_ISSUED, consumer = "m7.repayments")
    public void onIssued(JsonNode payload, ScopeContext scope) {
        JsonNode document = payload.path("document");
        JsonNode payment = payload.path("payment");
        record.handle(
                new RecordTillPayment(
                        uuid(document, "document_id"),
                        uuid(document, "series_id"),
                        document.hasNonNull("doc_number")
                                ? document.path("doc_number").asLong()
                                : null,
                        text(document, "doc_number_display"),
                        uuid(document, "location_id"),
                        uuid(document, "till_position_id"),
                        uuid(document, "device_id"),
                        date(document, "business_date"),
                        uuid(document, "operator_user_id"),
                        uuid(payment, "customer_account_id"),
                        text(payment, "method"),
                        decimal(payment, "amount"),
                        text(payment, "reference")),
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

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }
}
