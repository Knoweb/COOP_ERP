package lk.coopfed.knoweb.m8reporting.query;

import java.time.Instant;
import java.util.UUID;

/**
 * A report run (28A section 5: "runs/{runId} get -> {status, objectKey?, freshness}").
 *
 * @param status    REQUESTED, READY or FAILED
 * @param objectKey the PDF's key in the object store, once READY
 * @param errorCode the renderer's message id, when FAILED
 */
public record ReportRunView(
        UUID runId,
        String reportId,
        String status,
        String language,
        String objectKey,
        String errorCode,
        Instant requestedAt,
        Instant completedAt) {}
