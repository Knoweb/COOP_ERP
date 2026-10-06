package lk.coopfed.knoweb.m9integration.internal.journal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.RecordJournalPostings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The counterparty's journal consumer ({@code m9.journal.buyer}; wave 2, CR-19A-13, CR-29-1 item 3;
 * {@code docs/progress/deviations/2026-10-06-wave2-buyer-postings.md} (2)): the kernel delivers
 * the seller's {@code journal.postings_ready.v1} to it in the OWN scope of the payload's
 * {@code counterpartyEntityId}, the buyer, after checking that the buyer is the counterparty of the
 * document the event names. Here the buyer's side of the invoice or credit note (GRN_ACCRUAL and
 * VAT_INPUT against PAYABLE; PAYABLE against INVENTORY and VAT_INPUT) is recorded in the buyer's
 * own books, under the ordinary {@code own_write} policy, with no click by the buyer: the payable
 * exists when the invoice is issued.
 *
 * <p>Only the lines of the counterparty's side are taken, which is the side opposite to the
 * document type's issuer role (BUYER for INV and CN, whose issuer is the SELLER). The owner's
 * side is the owner's books, recorded by {@code m9.journal} in the owner's scope. A document whose
 * issuer role is neither SELLER nor BUYER has no counterparty side here and records nothing.
 *
 * <p>A consumer of one type, so the kernel hands it the payload itself (not the envelope); every
 * event that reaches it was published with the counterparty field, so the business date is
 * always present, and its absence is a defect ({@code m9.journal.postings_malformed}, which the
 * kernel retries and then dead-letters).
 */
@Component
class BuyerJournalPostingsConsumer {

    static final String CONSUMER = "m9.journal.buyer";

    private static final Logger log = LoggerFactory.getLogger(BuyerJournalPostingsConsumer.class);

    private final Handles<RecordJournalPostings, Integer> record;
    private final DocumentTypes documentTypes;

    BuyerJournalPostingsConsumer(Handles<RecordJournalPostings, Integer> record, DocumentTypes documentTypes) {
        this.record = record;
        this.documentTypes = documentTypes;
    }

    @EventConsumer(
            types = JournalPostingsConsumer.POSTINGS_READY,
            consumer = CONSUMER,
            party = EventConsumer.Party.COUNTERPARTY)
    public void on(JsonNode payload, ScopeContext scope) {
        String docTypeCode = text(payload, "docTypeCode");
        String side = counterpartySide(docTypeCode);
        if (side == null) {
            log.debug(
                    "journal.postings_ready.v1 of {} {} has no counterparty side; nothing for {}",
                    docTypeCode,
                    text(payload, "documentId"),
                    CONSUMER);
            return;
        }
        List<RecordJournalPostings.Line> lines = new ArrayList<>();
        for (JsonNode posting : payload.path("postings")) {
            if (!side.equals(text(posting, "side"))) {
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
        if (lines.isEmpty()) {
            return;
        }
        String businessDate = text(payload, "businessDate");
        if (businessDate == null || businessDate.isBlank()) {
            throw new ProblemException("m9.journal.postings_malformed");
        }
        record.handle(
                new RecordJournalPostings(
                        uuid(payload, "documentId"),
                        docTypeCode,
                        text(payload, "docNumberDisplay"),
                        LocalDate.parse(businessDate),
                        lines),
                scope);
    }

    /** The side the counterparty keeps: the one the issuer does not, or null when the type has no two sides. */
    private String counterpartySide(String docTypeCode) {
        String issuer =
                documentTypes.find(docTypeCode).map(DocumentType::issuerRole).orElse(null);
        if ("SELLER".equals(issuer)) {
            return "BUYER";
        }
        if ("BUYER".equals(issuer)) {
            return "SELLER";
        }
        return null;
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
