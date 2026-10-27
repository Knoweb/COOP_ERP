package lk.coopfed.knoweb.m8reporting.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.api.CreateProfitCenter;
import lk.coopfed.knoweb.m8reporting.api.CreateShopTarget;
import lk.coopfed.knoweb.m8reporting.api.ExportReportCsv;
import lk.coopfed.knoweb.m8reporting.api.RequestReportRun;
import lk.coopfed.knoweb.m8reporting.api.UpdateShopTarget;
import lk.coopfed.knoweb.m8reporting.query.DailyShopReport;
import lk.coopfed.knoweb.m8reporting.query.Dashboard;
import lk.coopfed.knoweb.m8reporting.query.ExceptionItem;
import lk.coopfed.knoweb.m8reporting.query.MonthlyProfitCenterPnlReport;
import lk.coopfed.knoweb.m8reporting.query.MonthlyShopReport;
import lk.coopfed.knoweb.m8reporting.query.ProfitCenterView;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportRunView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetFilter;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetPerformanceReport;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetView;
import lk.coopfed.knoweb.m8reporting.web.generated.CreateProfitCenterRequest;
import lk.coopfed.knoweb.m8reporting.web.generated.CreateShopTargetRequest;
import lk.coopfed.knoweb.m8reporting.web.generated.DailyShopReportItem;
import lk.coopfed.knoweb.m8reporting.web.generated.DailyShopReportResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.DailyShopReportSummary;
import lk.coopfed.knoweb.m8reporting.web.generated.DashboardResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.DashboardTile;
import lk.coopfed.knoweb.m8reporting.web.generated.ExceptionItemResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.MonthlyDailyTrendItem;
import lk.coopfed.knoweb.m8reporting.web.generated.MonthlyProfitCenterPnlResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.MonthlyShopReportItem;
import lk.coopfed.knoweb.m8reporting.web.generated.MonthlyShopReportResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.MonthlyShopReportSummary;
import lk.coopfed.knoweb.m8reporting.web.generated.ProfitCenterListResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ProfitCenterPnlItem;
import lk.coopfed.knoweb.m8reporting.web.generated.ProfitCenterPnlSummary;
import lk.coopfed.knoweb.m8reporting.web.generated.ProfitCenterResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportColumn;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportDataResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportDefinitionResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportRunResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportingApi;
import lk.coopfed.knoweb.m8reporting.web.generated.RequestReportRunRequest;
import lk.coopfed.knoweb.m8reporting.web.generated.ShopTargetPerformanceItem;
import lk.coopfed.knoweb.m8reporting.web.generated.ShopTargetPerformanceResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ShopTargetResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.TrendPoint;
import lk.coopfed.knoweb.m8reporting.web.generated.UpdateShopTargetRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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
    private final Handles<ExportReportCsv, ReportTable> exportCsv;
    private final Handles<CreateShopTarget, UUID> createTarget;
    private final Handles<UpdateShopTarget, Void> updateTarget;
    private final Handles<CreateProfitCenter, UUID> createProfitCenterHandler;
    private final Messages messages;
    private final ObjectProvider<A4Renderer> renderer;
    private final Clock clock;

    ReportingController(
            ReportingQueries queries,
            CurrentScope currentScope,
            Handles<RequestReportRun, UUID> requestRun,
            Handles<ExportReportCsv, ReportTable> exportCsv,
            Handles<CreateShopTarget, UUID> createTarget,
            Handles<UpdateShopTarget, Void> updateTarget,
            Handles<CreateProfitCenter, UUID> createProfitCenterHandler,
            Messages messages,
            ObjectProvider<A4Renderer> renderer,
            Clock clock) {
        this.queries = queries;
        this.currentScope = currentScope;
        this.requestRun = requestRun;
        this.exportCsv = exportCsv;
        this.createTarget = createTarget;
        this.updateTarget = updateTarget;
        this.createProfitCenterHandler = createProfitCenterHandler;
        this.messages = messages;
        this.renderer = renderer;
        this.clock = clock;
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
                        .drillReportId(tile.drillReportId())
                        .trend(tile.trend().stream()
                                .map(point -> new TrendPoint(
                                        point.from(), point.value().toPlainString()))
                                .toList()))
                .toList());
        response.setFreshness(dashboard.freshness());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<ExceptionItemResponse>> listExceptions() {
        return ResponseEntity.ok(queries.exceptions(currentScope.get()).stream()
                .map(ReportingController::toResponse)
                .toList());
    }

    private static ExceptionItemResponse toResponse(ExceptionItem item) {
        ExceptionItemResponse response = new ExceptionItemResponse(
                ExceptionItemResponse.KindEnum.fromValue(item.kind()),
                ExceptionItemResponse.SeverityEnum.fromValue(item.severity()),
                item.subjectId(),
                item.since(),
                item.escalated());
        response.setDocumentNumber(item.documentNumber());
        response.setRole(item.role() == null ? null : ExceptionItemResponse.RoleEnum.fromValue(item.role()));
        response.setCounterpartyEntityId(item.counterpartyEntityId());
        response.setCounterparty(item.counterparty());
        response.setSubject(item.subject());
        response.setAmount(item.amount() == null ? null : item.amount().toPlainString());
        response.setPercent(item.percent() == null ? null : item.percent().toPlainString());
        return response;
    }

    @Override
    public ResponseEntity<List<ReportRunResponse>> listReportRuns(String reportId) {
        ScopeContext scope = currentScope.get();
        // The history lists the runs only; a download link is asked for per run (getReportRun).
        return ResponseEntity.ok(queries.runs(reportId, scope).stream()
                .map(run -> {
                    ReportRunResponse response = new ReportRunResponse(
                            run.runId(),
                            run.reportId(),
                            ReportRunResponse.StatusEnum.fromValue(run.status()),
                            run.language(),
                            run.requestedAt());
                    response.setErrorCode(run.errorCode());
                    response.setCompletedAt(run.completedAt());
                    return response;
                })
                .toList());
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
        // Audited REPORT_EXPORTED (wave 2, M8-11); the screen's /data is not.
        ReportTable table = exportCsv.handle(new ExportReportCsv(reportId, from, to, locationId), scope);
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

    @Override
    public ResponseEntity<ShopTargetResponse> createShopTarget(String idempotencyKey, CreateShopTargetRequest request) {
        ScopeContext scope = currentScope.get();
        UUID targetId = createTarget.handle(
                new CreateShopTarget(
                        request.getLocationId(),
                        request.getPeriodStart(),
                        request.getPeriodEnd(),
                        new BigDecimal(request.getTargetAmount())),
                scope);
        return queries.getShopTarget(targetId, scope)
                .map(this::toResponse)
                .map(r -> ResponseEntity.status(HttpStatus.CREATED).body(r))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CREATED).build());
    }

    @Override
    public ResponseEntity<ShopTargetResponse> getShopTarget(UUID targetId) {
        ScopeContext scope = currentScope.get();
        return queries.getShopTarget(targetId, scope)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<ShopTargetResponse> updateShopTarget(
            String idempotencyKey, UUID targetId, UpdateShopTargetRequest request) {
        ScopeContext scope = currentScope.get();
        updateTarget.handle(
                new UpdateShopTarget(
                        targetId,
                        request.getPeriodStart(),
                        request.getPeriodEnd(),
                        new BigDecimal(request.getTargetAmount())),
                scope);
        return queries.getShopTarget(targetId, scope)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<ShopTargetPerformanceResponse> getShopTargetPerformance(
            @Nullable UUID locationId, @Nullable LocalDate from, @Nullable LocalDate to) {
        ScopeContext scope = currentScope.get();
        ShopTargetPerformanceReport report =
                queries.shopTargetPerformance(new ShopTargetFilter(locationId, from, to), scope);
        List<ShopTargetPerformanceItem> items = report.items().stream()
                .map(item -> {
                    ShopTargetPerformanceItem dto = new ShopTargetPerformanceItem(
                            item.targetId(),
                            item.locationId(),
                            item.locationCode(),
                            item.locationName(),
                            item.periodStart(),
                            item.periodEnd(),
                            item.targetAmount().toPlainString(),
                            item.actualAmount().toPlainString(),
                            item.varianceAmount().toPlainString(),
                            item.currency(),
                            item.createdAt(),
                            item.updatedAt());
                    if (item.achievementPercent() != null) {
                        dto.setAchievementPercent(item.achievementPercent().toPlainString());
                    }
                    return dto;
                })
                .toList();
        return ResponseEntity.ok(new ShopTargetPerformanceResponse(items));
    }

    private ShopTargetResponse toResponse(ShopTargetView view) {
        return new ShopTargetResponse(
                view.targetId(),
                view.locationId(),
                view.periodStart(),
                view.periodEnd(),
                view.targetAmount().toPlainString(),
                view.currency(),
                view.createdAt(),
                view.updatedAt());
    }

    @Override
    public ResponseEntity<DailyShopReportResponse> getDailyShopReport(
            @Nullable LocalDate date, @Nullable UUID locationId) {
        ScopeContext scope = currentScope.get();
        if (scope.locationId() != null
                && locationId != null
                && !scope.locationId().equals(locationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID effectiveLocationId = scope.locationId() != null ? scope.locationId() : locationId;
        try {
            DailyShopReport report = queries.dailyShopReport(date, effectiveLocationId, scope);
            return ResponseEntity.ok(toDailyShopReportResponse(report));
        } catch (ProblemException e) {
            if ("m8.shop_report.location_unknown".equals(e.messageId())) {
                return ResponseEntity.notFound().build();
            }
            if ("m8.shop_report.location_forbidden".equals(e.messageId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            throw e;
        }
    }

    @Override
    public ResponseEntity<MonthlyShopReportResponse> getMonthlyShopReport(
            @Nullable String yearMonth, @Nullable UUID locationId) {
        ScopeContext scope = currentScope.get();
        if (scope.locationId() != null
                && locationId != null
                && !scope.locationId().equals(locationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID effectiveLocationId = scope.locationId() != null ? scope.locationId() : locationId;
        try {
            MonthlyShopReport report = queries.monthlyShopReport(yearMonth, effectiveLocationId, scope);
            return ResponseEntity.ok(toMonthlyShopReportResponse(report));
        } catch (ProblemException e) {
            if ("m8.shop_report.location_unknown".equals(e.messageId())) {
                return ResponseEntity.notFound().build();
            }
            if ("m8.shop_report.location_forbidden".equals(e.messageId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            throw e;
        }
    }

    private DailyShopReportResponse toDailyShopReportResponse(DailyShopReport report) {
        var s = report.summary();
        DailyShopReportSummary summaryDto = new DailyShopReportSummary(
                s.businessDate(),
                s.totalGrossSales().toPlainString(),
                s.totalNetSales().toPlainString(),
                s.totalTaxAmount().toPlainString(),
                s.totalTransactions(),
                s.averageTransactionValue().toPlainString(),
                s.totalItemsSold().toPlainString(),
                s.currency());
        if (s.totalTargetAmount() != null) {
            summaryDto.setTotalTargetAmount(s.totalTargetAmount().toPlainString());
        }
        if (s.totalVarianceAmount() != null) {
            summaryDto.setTotalVarianceAmount(s.totalVarianceAmount().toPlainString());
        }
        if (s.overallAchievementPercent() != null) {
            summaryDto.setOverallAchievementPercent(
                    s.overallAchievementPercent().toPlainString());
        }

        List<DailyShopReportItem> shopsDto = report.shops().stream()
                .map(item -> {
                    DailyShopReportItem dto = new DailyShopReportItem(
                            item.locationId(),
                            item.locationCode(),
                            item.locationName(),
                            item.businessDate(),
                            item.grossSales().toPlainString(),
                            item.netSales().toPlainString(),
                            item.taxAmount().toPlainString(),
                            item.transactionCount(),
                            item.averageTransactionValue().toPlainString(),
                            item.itemsSold().toPlainString(),
                            DailyShopReportItem.PerformanceStateEnum.fromValue(
                                    item.performanceState().name()),
                            item.currency());
                    if (item.targetAmount() != null) {
                        dto.setTargetAmount(item.targetAmount().toPlainString());
                    }
                    if (item.varianceAmount() != null) {
                        dto.setVarianceAmount(item.varianceAmount().toPlainString());
                    }
                    if (item.achievementPercent() != null) {
                        dto.setAchievementPercent(item.achievementPercent().toPlainString());
                    }
                    return dto;
                })
                .toList();

        return new DailyShopReportResponse(summaryDto, shopsDto);
    }

    private MonthlyShopReportResponse toMonthlyShopReportResponse(MonthlyShopReport report) {
        var s = report.summary();
        MonthlyShopReportSummary summaryDto = new MonthlyShopReportSummary(
                s.yearMonth(),
                s.periodStart(),
                s.periodEnd(),
                s.totalGrossSales().toPlainString(),
                s.totalNetSales().toPlainString(),
                s.totalTaxAmount().toPlainString(),
                s.totalTransactions(),
                s.averageTransactionValue().toPlainString(),
                s.totalItemsSold().toPlainString(),
                s.currency());
        if (s.totalTargetAmount() != null) {
            summaryDto.setTotalTargetAmount(s.totalTargetAmount().toPlainString());
        }
        if (s.totalVarianceAmount() != null) {
            summaryDto.setTotalVarianceAmount(s.totalVarianceAmount().toPlainString());
        }
        if (s.overallAchievementPercent() != null) {
            summaryDto.setOverallAchievementPercent(
                    s.overallAchievementPercent().toPlainString());
        }

        List<MonthlyDailyTrendItem> trendDto = report.dailyTrend().stream()
                .map(item -> new MonthlyDailyTrendItem(
                        item.businessDate(),
                        item.grossSales().toPlainString(),
                        item.netSales().toPlainString(),
                        item.taxAmount().toPlainString(),
                        item.transactionCount(),
                        item.averageTransactionValue().toPlainString(),
                        item.itemsSold().toPlainString()))
                .toList();

        List<MonthlyShopReportItem> shopsDto = report.shops().stream()
                .map(item -> {
                    MonthlyShopReportItem dto = new MonthlyShopReportItem(
                            item.locationId(),
                            item.locationCode(),
                            item.locationName(),
                            item.grossSales().toPlainString(),
                            item.netSales().toPlainString(),
                            item.taxAmount().toPlainString(),
                            item.transactionCount(),
                            item.averageTransactionValue().toPlainString(),
                            item.itemsSold().toPlainString(),
                            MonthlyShopReportItem.PerformanceStateEnum.fromValue(
                                    item.performanceState().name()),
                            item.currency());
                    if (item.targetAmount() != null) {
                        dto.setTargetAmount(item.targetAmount().toPlainString());
                    }
                    if (item.varianceAmount() != null) {
                        dto.setVarianceAmount(item.varianceAmount().toPlainString());
                    }
                    if (item.achievementPercent() != null) {
                        dto.setAchievementPercent(item.achievementPercent().toPlainString());
                    }
                    return dto;
                })
                .toList();

        return new MonthlyShopReportResponse(summaryDto, trendDto, shopsDto);
    }

    @Override
    public ResponseEntity<ProfitCenterResponse> createProfitCenter(
            String idempotencyKey, CreateProfitCenterRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = createProfitCenterHandler.handle(
                new CreateProfitCenter(
                        request.getCode(),
                        request.getName(),
                        request.getCategory() != null ? request.getCategory().getValue() : null,
                        request.getLocationId()),
                scope);

        ProfitCenterView pc = queries.listProfitCenters(scope).stream()
                .filter(c -> c.profitCenterId().equals(id))
                .findFirst()
                .orElseGet(() -> new ProfitCenterView(
                        id,
                        scope.entityId(),
                        request.getCode(),
                        request.getName(),
                        request.getCategory() != null ? request.getCategory().getValue() : "GENERAL",
                        request.getLocationId(),
                        null,
                        "ACTIVE",
                        clock.instant()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toProfitCenterResponse(pc));
    }

    @Override
    public ResponseEntity<ProfitCenterListResponse> listProfitCenters() {
        List<ProfitCenterView> list = queries.listProfitCenters(currentScope.get());
        List<ProfitCenterResponse> items =
                list.stream().map(ReportingController::toProfitCenterResponse).toList();
        return ResponseEntity.ok(new ProfitCenterListResponse(items));
    }

    @Override
    public ResponseEntity<MonthlyProfitCenterPnlResponse> getMonthlyProfitCenterPnl(String month, UUID profitCenterId) {
        MonthlyProfitCenterPnlReport report = queries.monthlyProfitCenterPnl(month, profitCenterId, currentScope.get());
        return ResponseEntity.ok(toMonthlyProfitCenterPnlResponse(report));
    }

    private static ProfitCenterResponse toProfitCenterResponse(ProfitCenterView view) {
        ProfitCenterResponse response = new ProfitCenterResponse(
                view.profitCenterId(),
                view.ownerEntityId(),
                view.code(),
                view.name(),
                view.category(),
                view.status(),
                view.createdAt());
        response.setLocationId(view.locationId());
        response.setLocationName(view.locationName());
        return response;
    }

    private static MonthlyProfitCenterPnlResponse toMonthlyProfitCenterPnlResponse(
            MonthlyProfitCenterPnlReport report) {
        ProfitCenterPnlSummary summaryDto = new ProfitCenterPnlSummary(
                report.summary().totalRevenue().toPlainString(),
                report.summary().costPending());
        if (report.summary().totalCost() != null) {
            summaryDto.setTotalCost(report.summary().totalCost().toPlainString());
        }
        if (report.summary().grossProfit() != null) {
            summaryDto.setGrossProfit(report.summary().grossProfit().toPlainString());
        }
        if (report.summary().marginPercent() != null) {
            summaryDto.setMarginPercent(report.summary().marginPercent().toPlainString());
        }

        List<ProfitCenterPnlItem> itemsDto =
                report.items().stream().map(ReportingController::toPnlItemDto).toList();

        MonthlyProfitCenterPnlResponse response =
                new MonthlyProfitCenterPnlResponse(report.month(), report.currency(), summaryDto, itemsDto);

        if (report.unallocated() != null) {
            response.setUnallocated(toPnlItemDto(report.unallocated()));
        }
        return response;
    }

    private static ProfitCenterPnlItem toPnlItemDto(lk.coopfed.knoweb.m8reporting.query.ProfitCenterPnlItem item) {
        ProfitCenterPnlItem dto = new ProfitCenterPnlItem(
                item.code(),
                item.name(),
                item.category(),
                item.revenue().toPlainString(),
                item.costPending(),
                item.txnCount());
        dto.setProfitCenterId(item.profitCenterId());
        dto.setLocationId(item.locationId());
        dto.setLocationName(item.locationName());
        if (item.cost() != null) {
            dto.setCost(item.cost().toPlainString());
        }
        if (item.grossProfit() != null) {
            dto.setGrossProfit(item.grossProfit().toPlainString());
        }
        if (item.marginPercent() != null) {
            dto.setMarginPercent(item.marginPercent().toPlainString());
        }
        return dto;
    }
}
