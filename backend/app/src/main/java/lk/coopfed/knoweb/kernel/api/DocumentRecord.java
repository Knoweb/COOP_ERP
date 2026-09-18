package lk.coopfed.knoweb.kernel.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shared document header (doc 18 §5, table {@code document}). Immutable once
 * {@code issuedAt} is set: after that only {@code status} may change, and only
 * through a state transition (invariant C-I1). Type-specific data lives in the
 * owning module's extension tables keyed by {@code id}.
 */
public record DocumentRecord(
        UUID id,
        String docTypeCode,
        UUID seriesId,
        Long docNumber,
        String docNumberDisplay,
        UUID ownerEntityId,
        UUID counterpartyEntityId,
        UUID locationId,
        UUID tillPositionId,
        UUID deviceId,
        String status,
        Instant issuedAt,
        LocalDateTime issuedLocal,
        LocalDate businessDate,
        UUID operatorUserId,
        String currency,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        UUID referenceDocumentId,
        String contentHash,
        DocumentOrigin origin,
        Long deviceSeq,
        String notes) {

    public boolean isIssued() {
        return issuedAt != null;
    }

    /** The only change an issued document accepts: a new status, via the state history. */
    public DocumentRecord withStatus(String newStatus) {
        return new DocumentRecord(
                id, docTypeCode, seriesId, docNumber, docNumberDisplay,
                ownerEntityId, counterpartyEntityId, locationId, tillPositionId, deviceId,
                newStatus, issuedAt, issuedLocal, businessDate, operatorUserId,
                currency, netAmount, taxAmount, grossAmount, referenceDocumentId,
                contentHash, origin, deviceSeq, notes);
    }
}
