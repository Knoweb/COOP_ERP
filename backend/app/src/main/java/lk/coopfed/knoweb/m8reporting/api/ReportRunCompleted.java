package lk.coopfed.knoweb.m8reporting.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A report run ended: READY with the PDF's object key, or FAILED with the renderer's message id.
 */
public record ReportRunCompleted(UUID runId, String reportId, String status, String objectKey, String errorCode)
        implements DomainEvent {

    public static final String TYPE = "report_run.completed.v1";
}
