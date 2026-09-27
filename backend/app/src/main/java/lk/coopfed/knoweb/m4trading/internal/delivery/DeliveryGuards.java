package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guards the delivery note handlers share. */
final class DeliveryGuards {

    private DeliveryGuards() {}

    /** The seller's own note, locked for the transaction. */
    static DocumentRecord ownNote(DocumentBaseRepository documents, UUID noteId, ScopeContext scope) {
        DocumentRecord note = documents
                .findById(noteId)
                .filter(document -> DeliveryReads.DN.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.delivery.not_found"));
        if (!note.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m4.delivery.not_seller");
        }
        return documents.findByIdForUpdate(noteId).orElseThrow();
    }
}
