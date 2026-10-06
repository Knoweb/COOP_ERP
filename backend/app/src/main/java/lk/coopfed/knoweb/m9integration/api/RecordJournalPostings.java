package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The postings of one issued document, as the owning module published them on
 * {@code journal.postings_ready.v1} (29A section 6.2), recorded for the accounting export. The
 * consumer of that event builds it; M9 places each amount between the two roles the owning
 * module's posting map named and never recomputes one.
 *
 * @param businessDate the document's business date as issued (the payload's {@code businessDate};
 *     for an event published before that field existed, the day of the event's time)
 */
public record RecordJournalPostings(
        UUID documentId, String docTypeCode, String docNumberDisplay, LocalDate businessDate, List<Line> postings) {

    /** One posting: a line kind and side, the debited and credited roles, the amount and its source. */
    public record Line(
            String lineKind,
            String side,
            String debitRole,
            String creditRole,
            String amountSource,
            BigDecimal amount) {}
}
