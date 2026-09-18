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
     * Inserts a draft, or updates the status of an issued document.
     *
     * @throws ProblemException {@code document.immutable} when anything but the status
     *                          of an issued document would change
     */
    DocumentRecord save(DocumentRecord document);

    /** Appends lines to a document. Lines are never updated. */
    void saveLines(
            UUID documentId,
            List<DocumentLineRecord> lines);

    /** Appends a typed link from a correcting document to its original. */
    void addLink(DocumentLinkRecord link);

    /** Appends a state transition; the header's status follows it. */
    void addStateTransition(DocumentStateHistoryRecord transition);

    Optional<DocumentRecord> findById(UUID id);

    List<DocumentLineRecord> findLines(UUID documentId);

    /** Links in both directions: as the corrected original and as the correcting document. */
    List<DocumentLinkRecord> findLinks(UUID documentId);

    List<DocumentStateHistoryRecord> findHistory(UUID documentId);
}
