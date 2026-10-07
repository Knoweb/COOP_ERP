package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The debit note queries; row-level security decides what a caller sees. */
public interface DebitNoteQueries {

    Optional<DebitNoteView> getDebitNote(UUID debitNoteId, ScopeContext scope);

    /** The debit notes of one invoice, oldest first. */
    List<DebitNoteView> debitNotesOf(UUID invoiceId, ScopeContext scope);

    /** Where the printed A4 copy of a debit note is; empty until the worker has printed it. */
    Optional<String> printObjectKey(UUID debitNoteId, ScopeContext scope);
}
