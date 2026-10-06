package lk.coopfed.knoweb.m4trading.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * journal.postings_ready.v1 (doc 24 section 5.3): the journal lines of an issued document, for M9's export.
 *
 * @param ownerEntityId the entity that issued the document, whose books its own side's lines are in
 *     (the seller for INV, CN and PRC; the receiver for a GRN, whose lines are the BUYER side)
 * @param businessDate the document's business date as issued ({@code document.business_date},
 *     CR-19A-8; wave 2, CR-29-1 item 4): the date of every posting. A cheque reversal carries the
 *     reversal's own date. Added in wave 2: an event published before it has none, and M9 falls
 *     back to the event's time for it.
 */
public record JournalPostingsReady(
        UUID documentId,
        String docTypeCode,
        String docNumberDisplay,
        UUID ownerEntityId,
        List<Posting> postings,
        LocalDate businessDate)
        implements DomainEvent {

    public static final String TYPE = "journal.postings_ready.v1";
}
