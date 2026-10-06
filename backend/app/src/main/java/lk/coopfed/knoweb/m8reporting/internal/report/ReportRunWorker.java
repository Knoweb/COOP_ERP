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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ReportRunWorker (28A section 7): on {@code report_run.requested.v1}, on the worker role where
 * Chromium runs, reads the report's rows in the OWN scope of the run's entity (the scope the
 * consumer framework gives, at the requester's location), prints them with M8's template
 * {@code m8-report} through the kernel's A4Renderer (K-06b) in the run's language, and records
 * the outcome through {@link CompleteReportRunHandler}: READY with the PDF's key, or FAILED with
 * the renderer's message id.
 *
 * <p>The read and the print run in a read-only transaction of their own (wave 2, M8-12): a read
 * that throws (an unknown report, a period too long, a database error) marks only that
 * transaction for rollback, and the outcome is recorded in the dispatcher's, so the run ends
 * FAILED instead of being retried and dead-lettered while it stays REQUESTED. Any failure counts:
 * a ProblemException keeps its message id, anything else is {@code m8.run.render_failed} (the
 * cause stays in the log, not on the screen). Registered only where rendering is switched on
 * ({@code coop-erp.report.enabled}, the worker), as M4's invoice print is.
 */
@Component
@ConditionalOnProperty(name = "coop-erp.report.enabled", havingValue = "true")
class ReportRunWorker {

    static final String CONSUMER = "m8.render";
    static final String TEMPLATE = "m8-report";
    static final String RENDER_FAILED = "m8.run.render_failed";

    private static final Logger log = LoggerFactory.getLogger(ReportRunWorker.class);

    private final ReportingQueries queries;
    private final A4Renderer renderer;
    private final ReportPrintModel printModel;
    private final Handles<CompleteReportRun, String> complete;
    private final TransactionTemplate readAndPrint;

    ReportRunWorker(
            ReportingQueries queries,
            A4Renderer renderer,
            ReportPrintModel printModel,
            Handles<CompleteReportRun, String> complete,
            PlatformTransactionManager transactions) {
        this.queries = queries;
        this.renderer = renderer;
        this.printModel = printModel;
        this.complete = complete;
        this.readAndPrint = new TransactionTemplate(transactions);
        this.readAndPrint.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readAndPrint.setReadOnly(true);
    }

    @EventConsumer(types = ReportRunRequested.TYPE, consumer = CONSUMER)
    public void onRequested(JsonNode payload, ScopeContext scope) {
        UUID runId = UUID.fromString(payload.path("runId").asText());
        String reportId = payload.path("reportId").asText();
        ScopeContext inLanguage =
                withLocale(scope, Locale.forLanguageTag(payload.path("language").asText("en")));
        ReportParameters parameters =
                new ReportParameters(date(payload, "from"), date(payload, "to"), uuid(payload, "reportLocationId"));
        String objectKey = null;
        String errorCode = null;
        try {
            // queries.report takes the scope, so the kernel's customizer applies it to this
            // transaction's own connection.
            objectKey = readAndPrint.execute(status -> {
                ReportTable table = queries.report(reportId, parameters, inLanguage);
                return renderer.render(
                                TEMPLATE,
                                printModel.model(table, parameters, inLanguage),
                                inLanguage.locale(),
                                inLanguage)
                        .objectKey();
            });
        } catch (ProblemException failed) {
            errorCode = failed.messageId();
        } catch (RuntimeException failed) {
            log.warn("Report run {} of {} failed", runId, reportId, failed);
            errorCode = RENDER_FAILED;
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
