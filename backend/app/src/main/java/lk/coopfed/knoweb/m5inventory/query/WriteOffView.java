package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A write-off (25A section 6.3; doc 25 flow 6.4): DRAFT, REQUESTED (issued, waiting for the
 * witness), WITNESSED (waiting for approval), POSTED or REJECTED.
 *
 * @param documentNo     the WOF document's number, from the submit on
 * @param value          the loss at the entity average (doc 25 DR-3), from the submit on
 * @param photosRequired the category or the location needs photographs before the submit
 * @param remoteWitness  the witness confirmed from the photographs, not in person (DR-4)
 */
public record WriteOffView(
        UUID writeOffId,
        UUID locationId,
        String category,
        String note,
        String status,
        UUID requestedBy,
        Instant requestedAt,
        Instant submittedAt,
        String documentNo,
        BigDecimal value,
        Integer band,
        UUID witnessUserId,
        Instant witnessedAt,
        boolean remoteWitness,
        UUID approverUserId,
        Instant decidedAt,
        String rejectReason,
        boolean photosRequired,
        List<Line> lines,
        List<Photo> photos) {

    public record Line(int lineNo, UUID batchId, UUID skuId, String condition, BigDecimal qty) {}

    /** @param status PENDING until the kernel verified the upload, then COMPLETE or FAILED */
    public record Photo(UUID attachmentId, String status) {}
}
