package lk.coopfed.knoweb.m8reporting.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.api.CreateProfitCenter;
import lk.coopfed.knoweb.m8reporting.api.CreateShopTarget;
import lk.coopfed.knoweb.m8reporting.api.ExportReportCsv;
import lk.coopfed.knoweb.m8reporting.api.RegisterAsset;
import lk.coopfed.knoweb.m8reporting.api.RequestReportRun;
import lk.coopfed.knoweb.m8reporting.api.UpdateShopTarget;
import lk.coopfed.knoweb.m8reporting.query.AssetFilter;
import lk.coopfed.knoweb.m8reporting.query.AssetView;
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
import lk.coopfed.knoweb.m8reporting.query.RevenueEstimatePlan;
import lk.coopfed.knoweb.m8reporting.query.RevenueEstimateResult;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetFilter;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetPerformanceReport;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetView;
import lk.coopfed.knoweb.m8reporting.web.generated.AssetListResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.AssetResponse;
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
import lk.coopfed.knoweb.m8reporting.web.generated.RegisterAssetRequest;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportColumn;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportDataResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportDefinitionResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportRunResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ReportingApi;
import lk.coopfed.knoweb.m8reporting.web.generated.RequestReportRunRequest;
import lk.coopfed.knoweb.m8reporting.web.generated.RevenueEstimateAssumptions;
import lk.coopfed.knoweb.m8reporting.web.generated.RevenueEstimateItemResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.RevenueEstimateRequest;
import lk.coopfed.knoweb.m8reporting.web.generated.RevenueEstimateResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ShopTargetPerformanceItem;
import lk.coopfed.knoweb.m8reporting.web.generated.ShopTargetPerformanceResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.ShopTargetResponse;
import lk.coopfed.knoweb.m8reporting.web.generated.TrendPoint;
import lk.coopfed.knoweb.m8reporting.web.generated.UpdateShopTargetRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private final Handles<RegisterAsset, UUID> registerAssetHandler;
    private final Messages messages;
    private final ObjectProvider<A4Renderer> renderer;
    private final Clock clock;
    private final PermissionResolver permissionResolver;
    private final boolean enforcePermissions;

    ReportingController(
            ReportingQueries queries,
            CurrentScope currentScope,
            Handles<RequestReportRun, UUID> requestRun,
            Handles<ExportReportCsv, ReportTable> exportCsv,
            Handles<CreateShopTarget, UUID> createTarget,
            Handles<UpdateShopTarget, Void> updateTarget,
            Handles<CreateProfitCenter, UUID> createProfitCenterHandler,
            Handles<RegisterAsset, UUID> registerAssetHandler,
            Messages messages,
            ObjectProvider<A4Renderer> renderer,
            Clock clock,
            PermissionResolver permissionResolver,
            @Value("${coop-erp.security.enforce-permissions:true}") boolean enforcePermissions) {
        this.queries = queries;
        this.currentScope = currentScope;
        this.requestRun = requestRun;
        this.exportCsv = exportCsv;
        this.createTarget = createTarget;
        this.updateTarget = updateTarget;
        this.createProfitCenterHandler = createProfitCenterHandler;
        this.registerAssetHandler = registerAssetHandler;
        this.messages = messages;
        this.renderer = renderer;
        this.clock = clock;
        this.permissionResolver = permissionResolver;
        this.enforcePermissions = enforcePermissions;
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

    @Override
    public ResponseEntity<AssetResponse> registerAsset(String idempotencyKey, RegisterAssetRequest request) {
        ScopeContext scope = currentScope.get();
        final BigDecimal acquisitionCost = (request.getAcquisitionCost() != null
                        && !request.getAcquisitionCost().isBlank())
                ? new BigDecimal(request.getAcquisitionCost())
                : null;
        RegisterAsset command = new RegisterAsset(
                request.getAssetCode(),
                request.getName(),
                request.getDescription(),
                request.getAssetType() != null ? request.getAssetType().getValue() : null,
                request.getLocationId(),
                request.getAcquisitionDate(),
                acquisitionCost);

        UUID assetId = registerAssetHandler.handle(command, scope);

        AssetView view = queries.getAsset(assetId, scope)
                .orElseGet(() -> new AssetView(
                        assetId,
                        scope.entityId(),
                        request.getAssetCode(),
                        request.getName(),
                        request.getDescription(),
                        request.getAssetType() != null ? request.getAssetType().getValue() : "OTHER",
                        request.getLocationId(),
                        null,
                        request.getAcquisitionDate(),
                        acquisitionCost,
                        "ACTIVE",
                        clock.instant()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toAssetResponse(view));
    }

    @Override
    public ResponseEntity<AssetListResponse> listAssets(
            @Nullable UUID locationId, @Nullable String assetType, @Nullable String status) {
        ScopeContext scope = currentScope.get();
        if (scope.locationId() != null
                && locationId != null
                && !scope.locationId().equals(locationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID effectiveLocationId = scope.locationId() != null ? scope.locationId() : locationId;
        AssetFilter filter = new AssetFilter(effectiveLocationId, assetType, status);
        List<AssetView> assets = queries.listAssets(filter, scope);
        List<AssetResponse> items =
                assets.stream().map(ReportingController::toAssetResponse).toList();
        return ResponseEntity.ok(new AssetListResponse(items));
    }

    @Override
    public ResponseEntity<AssetResponse> getAsset(UUID assetId) {
        ScopeContext scope = currentScope.get();
        Optional<AssetView> asset = queries.getAsset(assetId, scope);
        return asset.map(view -> ResponseEntity.ok(toAssetResponse(view)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static AssetResponse toAssetResponse(AssetView view) {
        AssetResponse response = new AssetResponse(
                view.assetId(),
                view.ownerEntityId(),
                view.assetCode(),
                view.name(),
                view.assetType(),
                view.status(),
                view.createdAt());
        response.setDescription(view.description());
        response.setLocationId(view.locationId());
        response.setLocationName(view.locationName());
        response.setAcquisitionDate(view.acquisitionDate());
        if (view.acquisitionCost() != null) {
            response.setAcquisitionCost(view.acquisitionCost().toPlainString());
        }
        return response;
    }

    @Override
    public ResponseEntity<RevenueEstimateResponse> estimateRevenue(
            String idempotencyKey, RevenueEstimateRequest request) {
        ScopeContext scope = currentScope.get();
        if (scope.policyClass() != PolicyClass.OWN || scope.userId() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (enforcePermissions && !permissionResolver.allows(scope, "rpt.target.view")) {
            throw new ProblemException("permission.denied", Map.of("permission", "rpt.target.view"));
        }
        if (request == null) {
            throw new ProblemException("m8.estimator.mix_empty");
        }
        if (scope.locationId() != null
                && request.getLocationId() != null
                && !scope.locationId().equals(request.getLocationId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID effectiveLocationId = scope.locationId() != null ? scope.locationId() : request.getLocationId();

        BigDecimal targetRevenue;
        try {
            targetRevenue = request.getTargetRevenue() != null ? new BigDecimal(request.getTargetRevenue()) : null;
        } catch (NumberFormatException e) {
            throw new ProblemException("m8.estimator.target_revenue_invalid");
        }

        List<RevenueEstimatePlan.MixItem> items = request.getItems() == null
                ? List.of()
                : request.getItems().stream()
                        .map(i -> {
                            BigDecimal mix;
                            try {
                                mix = i.getMixPercent() != null ? new BigDecimal(i.getMixPercent()) : null;
                            } catch (NumberFormatException e) {
                                throw new ProblemException("m8.estimator.mix_must_equal_100");
                            }
                            return new RevenueEstimatePlan.MixItem(i.getSkuId(), mix);
                        })
                        .toList();

        RevenueEstimatePlan plan = new RevenueEstimatePlan(
                targetRevenue,
                effectiveLocationId,
                request.getPriceType() != null ? request.getPriceType().toString() : "RETAIL",
                request.getBusinessDate(),
                items);

        RevenueEstimateResult result = queries.estimateRevenue(plan, scope);
        return ResponseEntity.ok(toResponse(result));
    }

    private static RevenueEstimateResponse toResponse(RevenueEstimateResult result) {
        RevenueEstimateResponse response = new RevenueEstimateResponse();
        response.setTargetRevenue(result.targetRevenue().toPlainString());
        response.setTotalEstimatedRevenue(result.totalEstimatedRevenue().toPlainString());
        response.setTargetVariance(result.targetVariance().toPlainString());
        response.setTotalMixPercent(result.totalMixPercent().toPlainString());
        response.setCurrency(result.currency());
        response.setLocationId(result.locationId());
        response.setPriceType(result.priceType());
        response.setBusinessDate(result.businessDate());

        List<RevenueEstimateItemResponse> itemDtos = result.items().stream()
                .map(item -> {
                    RevenueEstimateItemResponse dto = new RevenueEstimateItemResponse();
                    dto.setSkuId(item.skuId());
                    dto.setSkuCode(item.skuCode());
                    dto.setSkuName(item.skuName());
                    dto.setUomCode(item.uomCode());
                    dto.setUnitPrice(item.unitPrice().toPlainString());
                    dto.setPriceSource(item.priceSource());
                    dto.setMixPercent(item.mixPercent().toPlainString());
                    dto.setTargetShare(item.targetShare().toPlainString());
                    dto.setRawQuantity(item.rawQuantity().toPlainString());
                    dto.setRoundedQuantity(item.roundedQuantity().toPlainString());
                    dto.setEstimatedRevenue(item.estimatedRevenue().toPlainString());
                    dto.setRoundingVariance(item.roundingVariance().toPlainString());
                    return dto;
                })
                .toList();
        response.setItems(itemDtos);

        RevenueEstimateAssumptions assumptionsDto = new RevenueEstimateAssumptions();
        assumptionsDto.setTargetRevenue(result.assumptions().targetRevenue());
        assumptionsDto.setLocationId(result.assumptions().locationId());
        assumptionsDto.setPriceType(result.assumptions().priceType());
        assumptionsDto.setBusinessDate(result.businessDate());
        assumptionsDto.setRoundingMethod(result.assumptions().roundingMethod());
        assumptionsDto.setPriceResolutionPolicy(result.assumptions().priceResolutionPolicy());
        response.setAssumptions(assumptionsDto);

        return response;
    }
}
