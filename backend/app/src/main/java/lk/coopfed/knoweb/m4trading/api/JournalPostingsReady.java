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
 * @param postings the lines of both sides where the document has two: the owner's side, and the
 *     counterparty's (an invoice's and a credit note's BUYER lines; wave 2, CR-24A-3 item 5). M9
 *     records each side in that party's own books: the owner's by the owner's consumer, the
 *     counterparty's by counterparty delivery (CR-19A-13). A PRC carries the seller's side only
 *     (the buyer's cash book is its own record, doc 10 A-01); a GRN the receiver's.
 * @param businessDate the document's business date as issued ({@code document.business_date},
 *     CR-19A-8; wave 2, CR-29-1 item 4): the date of every posting. A cheque reversal carries the
 *     reversal's own date. Added in wave 2: an event published before it has none, and M9 falls
 *     back to the event's time for it.
 * @param counterpartyEntityId the other party of the document when the event carries its lines
 *     (the buyer of an INV or CN), the entity the kernel delivers the event to for them; null
 *     when the event carries the owner's side only (GRN, PRC). Added in wave 2 (CR-19A-13): an
 *     event published before it has none and is passed over by the counterparty consumer.
 */
public record JournalPostingsReady(
        UUID documentId,
        String docTypeCode,
        String docNumberDisplay,
        UUID ownerEntityId,
        List<Posting> postings,
        LocalDate businessDate,
        UUID counterpartyEntityId)
        implements DomainEvent {

    public static final String TYPE = "journal.postings_ready.v1";
}
