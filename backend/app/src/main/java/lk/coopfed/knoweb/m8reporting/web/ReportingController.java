package lk.coopfed.knoweb.m8reporting.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.api.RequestReportRun;
import lk.coopfed.knoweb.m8reporting.query.Dashboard;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportRunView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import lk.coopfed.knoweb.m8reporting.web.generated.DashboardResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.DashboardTile;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportColumn;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportDataResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportDefinitionResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportRunResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportingApi;
import lk.coopfed.knoweb.m8reporting.web.generated.RequestReportRunRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of M8 for the demo (28A section 5): the dashboard, the report definitions, a
 * report's rows and its CSV, and a print run. The kernel checks each operation's x-permission;
 * row-level security decides which rows the caller sees; the stock value, a cost, is left out of
 * an answer to anybody but the owner's users and the Federation view (ReportQueriesImpl).
 */
@RestController
class ReportingController implements ReportingApi {

    private final ReportingQueries queries;
    private final CurrentScope currentScope;
    private final Handles<RequestReportRun, UUID> requestRun;
    private final Messages messages;
    private final ObjectProvider<A4Renderer> renderer;

    ReportingController(
            ReportingQueries queries,
            CurrentScope currentScope,
            Handles<RequestReportRun, UUID> requestRun,
            Messages messages,
            ObjectProvider<A4Renderer> renderer) {
        this.queries = queries;
        this.currentScope = currentScope;
        this.requestRun = requestRun;
        this.messages = messages;
        this.renderer = renderer;
    }

    @Override
    public ResponseEntity<DashboardResponse> getDashboard() {
        Dashboard dashboard = queries.dashboard(currentScope.get());
        DashboardResponse response = new DashboardResponse(dashboard.tiles().stream()
                .map(tile -> new DashboardTile(
                                tile.tileId(),
                                tile.labelId(),
                                tile.value().toPlainString(),
                                DashboardTile.KindEnum.fromValue(tile.kind()))
                        .drillReportId(tile.drillReportId()))
                .toList());
        response.setFreshness(dashboard.freshness());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<ReportDefinitionResponse>> listReports() {
        return ResponseEntity.ok(queries.definitions(currentScope.get()).stream()
                .map(d -> new ReportDefinitionResponse(
                        d.reportId(), d.titleId(), d.decisionId(), d.period(), d.location()))
                .toList());
    }

    @Override
    public ResponseEntity<ReportDataResponse> getReportData(
            String reportId, LocalDate from, LocalDate to, UUID locationId) {
        ReportTable table = queries.report(reportId, new ReportParameters(from, to, locationId), currentScope.get());
        ReportDataResponse response = new ReportDataResponse(
                table.reportId(),
                table.titleId(),
                table.columns().stream()
                        .map(c -> new ReportColumn(c.key(), c.labelId(), ReportColumn.KindEnum.fromValue(c.kind())))
                        .toList(),
                table.rows(),
                table.totals(),
                table.generatedAt());
        response.setFreshness(table.freshness());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<String> exportReportCsv(String reportId, LocalDate from, LocalDate to, UUID locationId) {
        ScopeContext scope = currentScope.get();
        ReportTable table = queries.report(reportId, new ReportParameters(from, to, locationId), scope);
        String name = reportId + (from == null ? "" : "_" + from + "_" + to) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(ReportCsv.write(table, messages, scope.locale()));
    }

    @Override
    public ResponseEntity<ReportRunResponse> requestReportRun(
            String idempotencyKey, String reportId, RequestReportRunRequest request) {
        ScopeContext scope = currentScope.get();
        UUID runId = requestRun.handle(
                new RequestReportRun(
                        reportId,
                        request.getFrom(),
                        request.getTo(),
                        request.getLocationId(),
                        request.getLanguage().getValue()),
                scope);
        return queries.run(runId, scope)
                .map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(run, scope)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }

    @Override
    public ResponseEntity<ReportRunResponse> getReportRun(UUID runId) {
        ScopeContext scope = currentScope.get();
        return queries.run(runId, scope)
                .map(run -> ResponseEntity.ok(toResponse(run, scope)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ReportRunResponse toResponse(ReportRunView run, ScopeContext scope) {
        ReportRunResponse response = new ReportRunResponse(
                run.runId(),
                run.reportId(),
                ReportRunResponse.StatusEnum.fromValue(run.status()),
                run.language(),
                run.requestedAt());
        response.setErrorCode(run.errorCode());
        response.setCompletedAt(run.completedAt());
        A4Renderer pdf = renderer.getIfAvailable();
        if (run.objectKey() != null && pdf != null) {
            // presignGet works on every role (the worker stored the PDF); a fresh link each time.
            response.setDownloadUrl(pdf.presignGet(run.objectKey(), scope));
        }
        return response;
    }
}
