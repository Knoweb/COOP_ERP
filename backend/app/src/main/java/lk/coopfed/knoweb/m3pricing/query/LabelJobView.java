package lk.coopfed.knoweb.m3pricing.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A shelf-label print job visible under the caller's row-level-security scope. */
public record LabelJobView(
        UUID jobId,
        UUID locationId,
        String status,
        int requestedCount,
        int renderedCount,
        List<LabelRefusal> refused,
        String objectKey,
        Instant createdAt) {

    public LabelJobView {
        refused = List.copyOf(refused);
    }
}
