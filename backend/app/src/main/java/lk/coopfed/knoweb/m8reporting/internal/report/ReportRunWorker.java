package lk.coopfed.knoweb.m8reporting.internal.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.api.ReportRunRequested;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ReportRunWorker (28A section 7): on {@code report_run.requested.v1}, on the worker role where
 * Chromium runs, reads the report's rows in the OWN scope of the run's entity (the scope the
 * consumer framework gives, at the requester's location), prints them with M8's template
 * {@code m8-report} through the kernel's A4Renderer (K-06b) in the run's language, and records
 * the outcome through {@link CompleteReportRunHandler}: READY with the PDF's key, or FAILED with
 * the renderer's message id. Registered only where rendering is switched on
 * ({@code coop-erp.report.enabled}, the worker), as M4's invoice print is.
 */
@Component
@ConditionalOnProperty(name = "coop-erp.report.enabled", havingValue = "true")
class ReportRunWorker {

    static final String CONSUMER = "m8.render";
    static final String TEMPLATE = "m8-report";

    private final ReportingQueries queries;
    private final A4Renderer renderer;
    private final ReportPrintModel printModel;
    private final Handles<CompleteReportRun, String> complete;

    ReportRunWorker(
            ReportingQueries queries,
            A4Renderer renderer,
            ReportPrintModel printModel,
            Handles<CompleteReportRun, String> complete) {
        this.queries = queries;
        this.renderer = renderer;
        this.printModel = printModel;
        this.complete = complete;
    }

    @EventConsumer(types = ReportRunRequested.TYPE, consumer = CONSUMER)
    public void onRequested(JsonNode payload, ScopeContext scope) {
        UUID runId = UUID.fromString(payload.path("runId").asText());
        String reportId = payload.path("reportId").asText();
        ScopeContext inLanguage = withLocale(scope, Locale.forLanguageTag(payload.path("language").asText("en")));
        ReportParameters parameters = new ReportParameters(
                date(payload, "from"), date(payload, "to"), uuid(payload, "reportLocationId"));
        String objectKey = null;
        String errorCode = null;
        try {
            ReportTable table = queries.report(reportId, parameters, inLanguage);
            objectKey = renderer.render(
                            TEMPLATE, printModel.model(table, parameters, inLanguage), inLanguage.locale(), inLanguage)
                    .objectKey();
        } catch (ProblemException failed) {
            errorCode = failed.messageId();
        }
        complete.handle(new CompleteReportRun(runId, objectKey, errorCode), scope);
    }

    private static ScopeContext withLocale(ScopeContext scope, Locale locale) {
        return new ScopeContext(
                scope.userId(),
                scope.deviceId(),
                scope.homeEntityId(),
                scope.scopes(),
                scope.activeScope(),
                scope.policyClass(),
                scope.grantedEntities(),
                scope.mfaAt(),
                locale,
                scope.correlationId());
    }

    private static LocalDate date(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? null : LocalDate.parse(value.asText());
    }

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? null : UUID.fromString(value.asText());
    }
}
