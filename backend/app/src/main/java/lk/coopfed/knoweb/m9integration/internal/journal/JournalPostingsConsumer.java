package lk.coopfed.knoweb.m9integration.internal.journal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.RecordJournalPostings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The journal consumer ({@code m9.journal}; 29A section 6.2): every {@code journal.postings_ready.v1}
 * an owning module publishes (M4 today, for its invoices, credit notes, payment receipts and their
 * reversals) becomes the postings of one document, held for the entity's next export.
 *
 * <p>A consumer of every type, as M8's projections are, because the envelope carries what the
 * payload lacks: the time the document was issued, whose day in the business time zone is the
 * posting's business date. The payload is read by field name (M9 imports no event record of
 * another module; the field names of M4's JournalPostingsReady and Posting are the contract). The
 * consumer runs in the OWN scope of the event's owner, so the postings are that entity's.
 */
@Component
class JournalPostingsConsumer {

    static final String POSTINGS_READY = "journal.postings_ready.v1";

    private final Handles<RecordJournalPostings, Integer> record;
    private final ZoneId zone;

    JournalPostingsConsumer(
            Handles<RecordJournalPostings, Integer> record, @Value("${coop-erp.business-timezone}") String zone) {
        this.record = record;
        this.zone = ZoneId.of(zone);
    }

    @EventConsumer(types = "*", consumer = "m9.journal")
    public void on(JsonNode envelope, ScopeContext scope) {
        if (!POSTINGS_READY.equals(envelope.path("eventType").asText(null))) {
            return;
        }
        JsonNode payload = envelope.path("payload");
        List<RecordJournalPostings.Line> lines = new ArrayList<>();
        for (JsonNode posting : payload.path("postings")) {
            lines.add(new RecordJournalPostings.Line(
                    text(posting, "lineKind"),
                    text(posting, "side"),
                    text(posting, "debitRole"),
                    text(posting, "creditRole"),
                    text(posting, "amountSource"),
                    decimal(posting, "amount")));
        }
        String occurredAt = text(envelope, "occurredAt");
        LocalDate businessDate = occurredAt == null
                ? null
                : Instant.parse(occurredAt).atZone(zone).toLocalDate();
        record.handle(
                new RecordJournalPostings(
                        uuid(payload, "documentId"),
                        text(payload, "docTypeCode"),
                        text(payload, "docNumberDisplay"),
                        businessDate,
                        lines),
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
}
