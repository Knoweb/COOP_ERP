package lk.coopfed.knoweb.m8reporting.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A report run was requested (28A section 7: "ReportRunWorker (@EventConsumer
 * report.requested.v1)"). The worker renders it. Named report_run.requested.v1 because the
 * kernel's own report.* events are the renderer's (report.rendered.v1).
 */
public record ReportRunRequested(
        UUID runId, String reportId, LocalDate from, LocalDate to, UUID reportLocationId, String language)
        implements DomainEvent {

    public static final String TYPE = "report_run.requested.v1";
}
