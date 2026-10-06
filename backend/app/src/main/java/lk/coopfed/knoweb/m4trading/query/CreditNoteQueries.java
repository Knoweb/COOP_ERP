package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The credit note queries (24A section 4, BillingQueries, demo scope); row-level security decides what a caller sees. */
public interface CreditNoteQueries {

    Optional<CreditNoteView> getCreditNote(UUID creditNoteId, ScopeContext scope);

    /** The credit notes of one invoice, oldest first. */
    List<CreditNoteView> creditNotesOf(UUID invoiceId, ScopeContext scope);

    /**
     * The seller's credit notes to the buyer that still hold money unapplied (CR-24A-3 item 2),
     * oldest first: what ApplyCreditNote can apply to an open invoice of the pair.
     */
    List<CreditNoteView> unappliedCreditNotes(UUID sellerEntityId, UUID buyerEntityId, ScopeContext scope);

    /** Where the printed A4 copy of a credit note is; empty until the worker has printed it. */
    Optional<String> printObjectKey(UUID creditNoteId, ScopeContext scope);
}
