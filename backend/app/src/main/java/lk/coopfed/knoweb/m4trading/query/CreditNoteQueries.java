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

    /** Where the printed A4 copy of a credit note is; empty until the worker has printed it. */
    Optional<String> printObjectKey(UUID creditNoteId, ScopeContext scope);
}
