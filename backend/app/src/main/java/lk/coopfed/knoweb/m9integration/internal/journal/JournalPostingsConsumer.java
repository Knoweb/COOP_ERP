package lk.coopfed.knoweb.m9integration.internal.journal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.RecordJournalPostings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The journal consumer ({@code m9.journal}; 29A section 6.2): every {@code journal.postings_ready.v1}
 * an owning module publishes (M4 today, for its invoices, credit notes, payment receipts and their
 * reversals, and the receiver's GRNs) becomes the postings of one document, held for the entity's
 * next export.
 *
 * <p>A consumer of every type, as M8's projections are. The payload is read by field name (M9
 * imports no event record of another module; the field names of M4's JournalPostingsReady and
 * Posting are the contract). The consumer runs in the OWN scope of the event's owner, so the
 * postings are that entity's.
 *
 * <p>Wave 2 (CR-29-1 item 4; {@code docs/progress/deviations/2026-10-06-wave2-buyer-postings.md}
 * (3), (5)): the business date is the document's own, {@code businessDate} in the payload; and only
 * the lines of the owner's own side are recorded. The owner is the document's issuer, so its side
 * is the issuer role of the document type in the kernel's registry (SELLER for INV, CN and PRC;
 * BUYER for a GRN). A line of the other side is the counterparty's, whose books are not this
 * scope's: it is left out here and recorded by {@link BuyerJournalPostingsConsumer}, which the
 * kernel runs in the counterparty's own scope (CR-19A-13). A type whose issuer is neither (HOLDER)
 * keeps every line.
 */
@Component
class JournalPostingsConsumer {

    static final String POSTINGS_READY = "journal.postings_ready.v1";

    private static final Logger log = LoggerFactory.getLogger(JournalPostingsConsumer.class);

    private final Handles<RecordJournalPostings, Integer> record;
    private final DocumentTypes documentTypes;
    private final ZoneId zone;

    JournalPostingsConsumer(
            Handles<RecordJournalPostings, Integer> record,
            DocumentTypes documentTypes,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.record = record;
        this.documentTypes = documentTypes;
        this.zone = ZoneId.of(zone);
    }

    @EventConsumer(types = "*", consumer = "m9.journal")
    public void on(JsonNode envelope, ScopeContext scope) {
        if (!POSTINGS_READY.equals(envelope.path("eventType").asText(null))) {
            return;
        }
        JsonNode payload = envelope.path("payload");
        String docTypeCode = text(payload, "docTypeCode");
        String ownSide = ownSide(docTypeCode);
        List<RecordJournalPostings.Line> lines = new ArrayList<>();
        int otherSide = 0;
        for (JsonNode posting : payload.path("postings")) {
            String side = text(posting, "side");
            if (ownSide != null && side != null && !ownSide.equals(side)) {
                otherSide++;
                continue;
            }
            lines.add(new RecordJournalPostings.Line(
                    text(posting, "lineKind"),
                    side,
                    text(posting, "debitRole"),
                    text(posting, "creditRole"),
                    text(posting, "amountSource"),
                    decimal(posting, "amount")));
        }
        if (otherSide > 0) {
            // Expected on every invoice and credit note since wave 2: the other side travels on
            // the same event and the counterparty consumer files it.
            log.debug(
                    "journal.postings_ready.v1 of {} {} carries {} posting(s) of the counterparty's side;"
                            + " left to the counterparty's books, not the {} side's",
                    docTypeCode,
                    text(payload, "documentId"),
                    otherSide,
                    ownSide);
            if (lines.isEmpty()) {
                return;
            }
        }
        record.handle(
                new RecordJournalPostings(
                        uuid(payload, "documentId"),
                        docTypeCode,
                        text(payload, "docNumberDisplay"),
                        businessDate(envelope, payload),
                        lines),
                scope);
    }

    /** The side whose books the owner keeps: the issuer role of the type when it is SELLER or BUYER, else null. */
    private String ownSide(String docTypeCode) {
        String role =
                documentTypes.find(docTypeCode).map(DocumentType::issuerRole).orElse(null);
        return "SELLER".equals(role) || "BUYER".equals(role) ? role : null;
    }

    /**
     * The document's business date as issued. An event published before the field existed has
     * none (it can only arrive by a replay); for it the day of the event's time in the business
     * time zone stands in, which is what this consumer used before wave 2.
     */
    private LocalDate businessDate(JsonNode envelope, JsonNode payload) {
        String issued = text(payload, "businessDate");
        if (issued != null && !issued.isBlank()) {
            return LocalDate.parse(issued);
        }
        String occurredAt = text(envelope, "occurredAt");
        return occurredAt == null
                ? null
                : Instant.parse(occurredAt).atZone(zone).toLocalDate();
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
}
