package lk.coopfed.knoweb.m8reporting.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of M8 (28A section 4, "query/ ReportingQueries"): the report definitions, a report's
 * rows, the dashboard, the exception queue and the report runs. Every method takes the caller's
 * scope; row-level security decides which rows a report is made of, so a report never names an
 * owner.
 */
public interface ReportingQueries {

    /** The definitions the caller may run, in the order the catalogue lists them. */
    List<ReportDefinitionView> definitions(ScopeContext scope);

    /**
     * A report's rows for the parameters.
     *
     * @throws lk.coopfed.knoweb.kernel.api.ProblemException {@code m8.report.unknown},
     *     {@code m8.report.period_required}, {@code m8.report.period_invalid},
     *     {@code m8.report.period_too_long} ({@code reporting.max_period_days}),
     *     {@code m8.report.too_many_rows} ({@code reporting.max_rows})
     */
    ReportTable report(String reportId, ReportParameters parameters, ScopeContext scope);

    /** The dashboard's tiles for the caller's scope. */
    Dashboard dashboard(ScopeContext scope);

    /** The exception queue of the caller's scope: escalated first, then alerts, oldest first. */
    List<ExceptionItem> exceptions(ScopeContext scope);

    /** A report run the caller's entity requested. */
    Optional<ReportRunView> run(UUID runId, ScopeContext scope);

    /**
     * The latest print runs of a report the caller's entity requested, newest first.
     *
     * @throws lk.coopfed.knoweb.kernel.api.ProblemException {@code m8.report.unknown}
     */
    List<ReportRunView> runs(String reportId, ScopeContext scope);

    /** Target vs actual sales comparison performance for shops in the caller's scope. */
    ShopTargetPerformanceReport shopTargetPerformance(ShopTargetFilter filter, ScopeContext scope);

    /** A shop sales target by ID in the caller's scope. */
    Optional<ShopTargetView> getShopTarget(UUID targetId, ScopeContext scope);

    /** Daily operational sales report for permitted shops on a business date. */
    DailyShopReport dailyShopReport(java.time.LocalDate date, UUID locationId, ScopeContext scope);

    /** Monthly operational sales report, daily trend, and cross-shop comparison for a month. */
    MonthlyShopReport monthlyShopReport(String yearMonth, UUID locationId, ScopeContext scope);

    /** List all active profit center dimensions in the caller's scope. */
    List<ProfitCenterView> listProfitCenters(ScopeContext scope);

    /** Month-end P&L by profit center for a year-month and optional profit center ID. */
    MonthlyProfitCenterPnlReport monthlyProfitCenterPnl(String yearMonth, UUID profitCenterId, ScopeContext scope);

    /** List assets according to the filter in the caller's scope (Polgolla M1-04). */
    List<AssetView> listAssets(AssetFilter filter, ScopeContext scope);

    /** Retrieve a single asset by ID in the caller's scope (Polgolla M1-04). */
    Optional<AssetView> getAsset(UUID assetId, ScopeContext scope);

    /**
     * Estimates required product quantities and sales mix to achieve a target revenue amount
     * using effective selling prices resolved from the pricing subsystem (Polgolla M1-03).
     */
    RevenueEstimateResult estimateRevenue(RevenueEstimatePlan plan, ScopeContext scope);

    /** Monthly debtor list for the caller's permitted scope (Polgolla M1-10). */
    DebtorsReport debtorsReport(java.time.LocalDate asOf, ScopeContext scope);

    /** Monthly creditor list for the caller's permitted scope (Polgolla M1-10). */
    CreditorsReport creditorsReport(java.time.LocalDate asOf, ScopeContext scope);

    /** Inter-entity opening balances list for the caller's entity (Polgolla M1-10). */
    OpeningBalancesReport openingBalancesReport(UUID counterpartyId, ScopeContext scope);

    /** Goods supplied summary across entities/locations (Polgolla M1-10). */
    GoodsSuppliedReport goodsSuppliedReport(
            java.time.LocalDate from, java.time.LocalDate to, UUID societyId, UUID locationId, ScopeContext scope);

    /** Credit-purchase voucher details for an invoice (Polgolla M1-10). */
    Optional<CreditVoucherView> getCreditVoucher(UUID invoiceId, ScopeContext scope);

    /** Render or obtain pre-signed link to printable credit voucher A4 PDF (Polgolla M1-10). */
    java.net.URI renderCreditVoucher(UUID invoiceId, ScopeContext scope);
}
