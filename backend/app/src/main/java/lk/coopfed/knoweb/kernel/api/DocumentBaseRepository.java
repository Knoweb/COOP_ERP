package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The document base every module's documents stand on (doc 18 Part C; 17A §4.3).
 * Headers are immutable once issued except for {@code status}; lines, links and
 * state history are insert-only. The 17A stub keeps them in memory; 19A backs them
 * with the kernel schema and the issuance protocol.
 */
public interface DocumentBaseRepository {

    /**
     * Inserts or updates a draft. An issued document is never saved: its status changes
     * through {@link #addStateTransition} and nothing else changes at all. A number, an
     * issuance time or a hash on the record is refused too: only the issuance protocol
     * ({@link DocumentIssuance}) sets them.
     *
     * @throws ProblemException {@code document.immutable} for an issued document,
     *                          {@code document.issued_fields_reserved} for a record that
     *                          carries what issuance sets
     */
    DocumentRecord save(DocumentRecord document);

    /**
     * Appends lines to a draft. Lines are never updated, and none joins an issued document
     * (the database refuses it with {@code document.immutable}).
     */
    void saveLines(UUID documentId, List<DocumentLineRecord> lines);

    /** Appends a typed link from a correcting document to its original. */
    void addLink(DocumentLinkRecord link);

    /**
     * Locks an original for the rest of the transaction before its corrections are summed, so
     * two concurrent settlements of one invoice see each other. {@link DocumentLinks} takes it;
     * a module that reads the open balance before it decides takes it too.
     */
    void lockForLinking(UUID documentId);

    /**
     * Moves the document from {@code fromStatus} to {@code toStatus}, appends the history row
     * and records the audit event {@code DOCUMENT_STATUS_CHANGED}, in the caller's transaction.
     * The move is compare-and-set: it happens only when the document is still in
     * {@code fromStatus}.
     *
     * @throws ProblemException {@code document.status_conflict} when the document is not in
     *                          {@code fromStatus}, or the transition names no change
     */
    void addStateTransition(DocumentStateHistoryRecord transition, ScopeContext ctx);

    Optional<DocumentRecord> findById(UUID id);

    /** The header, locked for the rest of the transaction; the issuance protocol takes it before it reads the lines. */
    Optional<DocumentRecord> findByIdForUpdate(UUID id);

    List<DocumentLineRecord> findLines(UUID documentId);

    /** Links in both directions: as the corrected original and as the correcting document. */
    List<DocumentLinkRecord> findLinks(UUID documentId);

    List<DocumentStateHistoryRecord> findHistory(UUID documentId);
}
