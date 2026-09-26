package lk.coopfed.knoweb.kernel.api;

import java.util.List;

/**
 * The issuance protocol (doc 18 section 5.5; 19A section 7), the one way a document gets a
 * number. Called by the owning module's command handler, inside its transaction, with a draft
 * and its lines; what comes back is the issued header with its number, display, hash,
 * timestamps and totals, already stored with its lines, its DRAFT to ISSUED state row and its
 * {@code DOCUMENT_ISSUED} audit record, and {@code document.issued.v1} published.
 *
 * <p>In order: the base's checks (status DRAFT, a known type, a counterparty where bilateral,
 * owner is the caller's scope entity); the type's {@link DocumentTypeHandler#validate}; the
 * number from the series of the type's scope; totals frozen from the lines; the content hash;
 * the rows; the audit record; {@link DocumentTypeHandler#afterIssue}; the event. A failure
 * anywhere rolls the whole back, number included.
 *
 * <p>Offline scopes are never issued here: a till numbers its own documents and they arrive
 * with the number through ingestion (K-08).
 */
public interface DocumentIssuance {

    /**
     * @param draft the header in status DRAFT, with an id, its type, its parties and its
     *              location; number, display, hash, issuance timestamps and totals are ignored
     *              and set here. The draft may already be stored (a module keeps drafts) or not;
     *              when it is, the stored header and lines are what is issued, locked for the
     *              transaction, and this record only names the document.
     * @param lines the lines, in line order; stored here when the document has none stored yet
     * @throws ProblemException {@code document.not_draft}, {@code document.type_unknown},
     *                          {@code document.type_unowned}, {@code document.counterparty_required},
     *                          {@code document.owner_mismatch}, {@code document.series_missing},
     *                          {@code document.series_device_held} (an OFFLINE draft, a series a
     *                          device holds or a TILL_POSITION series: the till numbers those),
     *                          {@code series.closed}, or whatever the type's validator raises
     */
    DocumentRecord issue(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx);
}
