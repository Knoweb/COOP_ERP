package lk.coopfed.knoweb.m8reporting.internal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness;
import lk.coopfed.knoweb.m8reporting.api.ReportRunCompleted;
import lk.coopfed.knoweb.m8reporting.api.RequestReportRun;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A print run from request to outcome (28A section 7, render/ReportRunWorker), with a renderer
 * that stands in for Chromium: the worker prints the report with M8's template in the run's
 * language and records READY with the key, or FAILED with the renderer's message id; the
 * outcome is written once. The guards of RequestReportRun that the HTTP test does not reach.
 */
class ReportRunPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190e889-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190e889-0000-7000-8000-000000000010");

    @Autowired
    Handles<RequestReportRun, UUID> request;

    @Autowired
    Handles<CompleteReportRun, String> complete;

    @Autowired
    ReportingQueries queries;

    @Autowired
    ReportPrintModel printModel;

    @Autowired
    ObjectMapper mapper;

    private JdbcTemplate admin;

    @BeforeEach
    void arrange() {
        admin = superuserJdbc();
        admin.execute("truncate table reporting.report_run");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        admin.execute("truncate table reporting.report_run");
    }

    @Test
    void theWorkerPrintsTheReportInTheRunsLanguageAndRecordsItReadyOnce() {
        UUID runId = request.handle(
                new RequestReportRun("stock-position", null, null, null, "ta"), ScopeContext.dev(USER, ENTITY, null));
        kernel.reset();

        RecordingRenderer renderer = new RecordingRenderer(null);
        worker(renderer).onRequested(payload(runId, "stock-position", null, null, "ta"), system());

        assertThat(renderer.templateId).isEqualTo("m8-report");
        assertThat(renderer.language).isEqualTo(Locale.forLanguageTag("ta"));
        assertThat(renderer.data.get("title")).isEqualTo("இருப்பு நிலை");
        assertThat(queries.run(runId, ScopeContext.dev(USER, ENTITY, null))
                        .orElseThrow()
                        .status())
                .isEqualTo("READY");
        assertThat(admin.queryForObject("select object_key from reporting.report_run", String.class))
                .isEqualTo("reports/" + ENTITY + "/r.pdf");
        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("REPORT_RUN_COMPLETED");
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new ReportRunCompleted(runId, "stock-position", "READY", "reports/" + ENTITY + "/r.pdf", null));

        // A redelivered request does not print a second outcome.
        kernel.reset();
        ProblemException again = assertThrows(
                ProblemException.class,
                () -> complete.handle(new CompleteReportRun(runId, "reports/x.pdf", null), system()));
        assertThat(again.messageId()).isEqualTo("m8.run.not_open");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aRendererFailureIsRecordedFailedWithItsMessageId() {
        UUID runId = request.handle(
                new RequestReportRun(
                        "invoices-issued", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, "en"),
                ScopeContext.dev(USER, ENTITY, null));

        worker(new RecordingRenderer(new ProblemException("report.timeout")))
                .onRequested(payload(runId, "invoices-issued", "2026-09-01", "2026-09-30", "en"), system());

        var run = queries.run(runId, ScopeContext.dev(USER, ENTITY, null)).orElseThrow();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("report.timeout");
    }

    @Test
    void anUnknownRunIsRefused() {
        ProblemException refused = assertThrows(
                ProblemException.class, () -> complete.handle(new CompleteReportRun(Ids.next(), "k", null), system()));
        assertThat(refused.messageId()).isEqualTo("m8.run.not_found");
    }

    @Test
    void aRunIsRequestedOnlyByAUserOfItsOwnEntityInALanguageOfTheCatalogue() {
        ProblemException noUser = assertThrows(
                ProblemException.class,
                () -> request.handle(new RequestReportRun("stock-position", null, null, null, "en"), system()));
        assertThat(noUser.messageId()).isEqualTo("m8.run.own_required");

        ProblemException unknown = assertThrows(
                ProblemException.class,
                () -> request.handle(
                        new RequestReportRun("no-such", null, null, null, "en"), ScopeContext.dev(USER, ENTITY, null)));
        assertThat(unknown.messageId()).isEqualTo("m8.report.unknown");

        ProblemException language = assertThrows(
                ProblemException.class,
                () -> request.handle(
                        new RequestReportRun("stock-position", null, null, null, "fr"),
                        ScopeContext.dev(USER, ENTITY, null)));
        assertThat(language.messageId()).isEqualTo("m8.run.language_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void thePrintModelFormatsEveryCellAndAddsATotalsRow() {
        ReportTable table = new ReportTable(
                "invoices-issued",
                "m8.report.invoices_issued.title",
                ReportCatalogue.INVOICES.columns(),
                List.of(Map.of(
                        "date", "2026-09-27",
                        "number", "F-INV-1",
                        "seller", "COOPFED",
                        "buyer", "D101",
                        "net", "902.50",
                        "tax", "162.45",
                        "gross", "1064.95")),
                Map.of("net", "902.50", "tax", "162.45", "gross", "1064.95"),
                null,
                Instant.parse("2026-09-27T06:00:00Z"));

        Map<String, Object> model = printModel.model(
                table,
                new ReportParameters(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null),
                ScopeContext.dev(USER, ENTITY, null));

        assertThat(model.get("title")).isEqualTo("Invoices issued");
        assertThat((String) model.get("subtitle")).startsWith("From ");
        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) model.get("rows");
        assertThat(rows.get(0)).hasSize(7);
        assertThat(rows.get(0).get(1)).isEqualTo("F-INV-1");
        assertThat(rows.get(0).get(6)).contains("1,064.95");
        @SuppressWarnings("unchecked")
        List<String> totals = (List<String>) model.get("totals");
        assertThat(totals.get(0)).isEqualTo("Total");
        assertThat(totals.get(6)).contains("1,064.95");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private ReportRunWorker worker(A4Renderer renderer) {
        return new ReportRunWorker(queries, renderer, printModel, complete);
    }

    private ObjectNode payload(UUID runId, String reportId, String from, String to, String language) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("runId", runId.toString());
        payload.put("reportId", reportId);
        payload.put("from", from);
        payload.put("to", to);
        payload.putNull("reportLocationId");
        payload.put("language", language);
        return payload;
    }

    private static ScopeContext system() {
        return ProjectionHarness.system(ENTITY, null);
    }

    /** Stands in for Chromium: records what it was asked to print, or fails as told. */
    static final class RecordingRenderer implements A4Renderer {

        private final ProblemException failure;
        String templateId;
        Map<String, Object> data;
        Locale language;
        final List<ScopeContext> scopes = new ArrayList<>();

        RecordingRenderer(ProblemException failure) {
            this.failure = failure;
        }

        @Override
        public Rendered render(String templateId, Map<String, Object> data, Locale language, ScopeContext ctx) {
            if (failure != null) {
                throw failure;
            }
            this.templateId = templateId;
            this.data = data;
            this.language = language;
            scopes.add(ctx);
            return new Rendered(
                    Ids.next(),
                    "reports/" + ctx.entityId() + "/r.pdf",
                    URI.create("http://objects.test/r.pdf"),
                    Instant.parse("2026-09-27T07:00:00Z"),
                    1,
                    "00");
        }

        @Override
        public URI presignGet(String objectKey, ScopeContext ctx) {
            return URI.create("http://objects.test/" + objectKey);
        }
    }
}
