import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { useReportingApi } from "./reportingApi";
import { errorText } from "./reportView";
import "./reporting.css";

export function ShopReportsPage() {
  const t = useT();
  const intl = useIntl();
  const api = useReportingApi();
  const formatDate = useFormatDate();

  const todayStr = businessToday();
  const currentMonthStr = todayStr.slice(0, 7);

  const [activeTab, setActiveTab] = useState<"daily" | "monthly">("daily");
  const [filterLocation, setFilterLocation] = useState<string>("");
  const [filterDate, setFilterDate] = useState<string>(todayStr);
  const [filterYearMonth, setFilterYearMonth] = useState<string>(currentMonthStr);

  const locationsQuery = useQuery({
    queryKey: ["reporting", "locations"],
    queryFn: () => api.locations(),
    staleTime: Infinity
  });

  const dailyQuery = useQuery({
    queryKey: ["reporting", "daily-shop-report", filterDate, filterLocation],
    queryFn: () =>
      api.dailyShopReport({
        date: filterDate || undefined,
        locationId: filterLocation || undefined
      }),
    enabled: activeTab === "daily"
  });

  const monthlyQuery = useQuery({
    queryKey: ["reporting", "monthly-shop-report", filterYearMonth, filterLocation],
    queryFn: () =>
      api.monthlyShopReport({
        yearMonth: filterYearMonth || undefined,
        locationId: filterLocation || undefined
      }),
    enabled: activeTab === "monthly"
  });

  function resolveState(state: "TARGET_MET" | "BEHIND_TARGET" | "NO_TARGET"): { chip: ChipState; label: string } {
    switch (state) {
      case "TARGET_MET":
        return { chip: "issued", label: t("reporting.targets.status.achieved").text };
      case "BEHIND_TARGET":
        return { chip: "alert", label: t("reporting.targets.status.behind").text };
      case "NO_TARGET":
      default:
        return { chip: "draft", label: t("reporting.targets.status.not_set").text };
    }
  }

  const isLoading = activeTab === "daily" ? dailyQuery.isLoading : monthlyQuery.isLoading;
  const isError = activeTab === "daily" ? dailyQuery.isError : monthlyQuery.isError;
  const error = activeTab === "daily" ? dailyQuery.error : monthlyQuery.error;

  const dailyReport = dailyQuery.data;
  const monthlyReport = monthlyQuery.data;

  return (
    <main className="shell-page">
      <PageHeader
        icon="society"
        title={t("reporting.shop_reports.title").text}
      />
      <p className="shop-targets-card__subtitle">{t("reporting.shop_reports.subtitle").text}</p>

      {/* Tabs */}
      <div className="reporting-tabs" role="tablist" style={{ display: "flex", gap: "var(--space-2)", marginBottom: "var(--space-3)" }}>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "daily"}
          className={`btn ${activeTab === "daily" ? "btn-primary" : "btn-secondary"}`}
          onClick={() => setActiveTab("daily")}
        >
          {t("reporting.shop_reports.tab.daily").text}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "monthly"}
          className={`btn ${activeTab === "monthly" ? "btn-primary" : "btn-secondary"}`}
          onClick={() => setActiveTab("monthly")}
        >
          {t("reporting.shop_reports.tab.monthly").text}
        </button>
      </div>

      {/* Filter Bar */}
      <div className="reporting-filter-bar">
        <div>
          <label htmlFor="shop-report-location" className="reporting-filter-label">
            {t("reporting.shop_reports.shop").text}
          </label>
          <select
            id="shop-report-location"
            className="form-control"
            value={filterLocation}
            onChange={(e) => setFilterLocation(e.target.value)}
          >
            <option value="">{t("reporting.shop_reports.all_shops").text}</option>
            {locationsQuery.data?.map((loc) => (
              <option key={loc.locationId} value={loc.locationId}>
                {locationText(loc, intl.locale)}
              </option>
            ))}
          </select>
        </div>

        {activeTab === "daily" && (
          <div>
            <label htmlFor="shop-report-date" className="reporting-filter-label">
              {t("reporting.shop_reports.date").text}
            </label>
            <input
              id="shop-report-date"
              type="date"
              className="form-control"
              value={filterDate}
              onChange={(e) => setFilterDate(e.target.value)}
            />
          </div>
        )}

        {activeTab === "monthly" && (
          <div>
            <label htmlFor="shop-report-month" className="reporting-filter-label">
              {t("reporting.shop_reports.month").text}
            </label>
            <input
              id="shop-report-month"
              type="month"
              className="form-control"
              value={filterYearMonth}
              onChange={(e) => setFilterYearMonth(e.target.value)}
            />
          </div>
        )}
      </div>

      {isLoading && <p>{t("reporting.loading").text}</p>}
      {isError && <p role="alert">{errorText(error, t("reporting.error.generic").text)}</p>}

      {/* Daily Report View */}
      {activeTab === "daily" && dailyReport && (
        <>
          {/* Summary KPIs */}
          <div className="targets-kpi-grid">
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.gross").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={dailyReport.summary.totalGrossSales} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.net").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={dailyReport.summary.totalNetSales} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.tax").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={dailyReport.summary.totalTaxAmount} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.txns").text}</span>
              <span className="targets-kpi-value">
                {dailyReport.summary.totalTransactions} ({t("reporting.shop_reports.col.atv").text}: <MoneyDisplay amount={dailyReport.summary.averageTransactionValue} />)
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.items").text}</span>
              <span className="targets-kpi-value">{dailyReport.summary.totalItemsSold}</span>
            </div>
            {dailyReport.summary.totalTargetAmount && (
              <>
                <div className="targets-kpi-card">
                  <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.target").text}</span>
                  <span className="targets-kpi-value">
                    <MoneyDisplay amount={dailyReport.summary.totalTargetAmount} />
                  </span>
                </div>
                <div className="targets-kpi-card">
                  <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.variance").text}</span>
                  <span
                    className={`targets-kpi-value ${
                      parseFloat(dailyReport.summary.totalVarianceAmount || "0") >= 0
                        ? "targets-kpi-positive"
                        : "targets-kpi-negative"
                    }`}
                  >
                    <MoneyDisplay amount={dailyReport.summary.totalVarianceAmount || "0"} />
                  </span>
                </div>
                <div className="targets-kpi-card">
                  <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.achievement").text}</span>
                  <span className="targets-kpi-value">
                    {dailyReport.summary.overallAchievementPercent
                      ? `${dailyReport.summary.overallAchievementPercent}%`
                      : "—"}
                  </span>
                </div>
              </>
            )}
          </div>

          {/* Daily Shops Breakdown Table */}
          {dailyReport.shops.length === 0 ? (
            <p>{t("reporting.shop_reports.empty").text}</p>
          ) : (
            <div style={{ overflowX: "auto" }}>
              <table className="reporting-data-table" aria-label={t("reporting.shop_reports.tab.daily").text}>
                <thead>
                  <tr>
                    <th>{t("reporting.shop_reports.col.code").text}</th>
                    <th>{t("reporting.shop_reports.col.name").text}</th>
                    <th>{t("reporting.shop_reports.col.gross").text}</th>
                    <th>{t("reporting.shop_reports.col.net").text}</th>
                    <th>{t("reporting.shop_reports.col.tax").text}</th>
                    <th>{t("reporting.shop_reports.col.txns").text}</th>
                    <th>{t("reporting.shop_reports.col.atv").text}</th>
                    <th>{t("reporting.shop_reports.col.items").text}</th>
                    <th>{t("reporting.shop_reports.col.target").text}</th>
                    <th>{t("reporting.shop_reports.col.variance").text}</th>
                    <th>{t("reporting.shop_reports.col.achievement").text}</th>
                    <th>{t("reporting.shop_reports.col.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {dailyReport.shops.map((shop) => {
                    const { chip, label } = resolveState(shop.performanceState);
                    const varianceVal = parseFloat(shop.varianceAmount || "0");
                    return (
                      <tr key={shop.locationId}>
                        <td>{shop.locationCode}</td>
                        <td>{shop.locationName}</td>
                        <td><MoneyDisplay amount={shop.grossSales} /></td>
                        <td><MoneyDisplay amount={shop.netSales} /></td>
                        <td><MoneyDisplay amount={shop.taxAmount} /></td>
                        <td>{shop.transactionCount}</td>
                        <td><MoneyDisplay amount={shop.averageTransactionValue} /></td>
                        <td>{shop.itemsSold}</td>
                        <td>{shop.targetAmount ? <MoneyDisplay amount={shop.targetAmount} /> : "—"}</td>
                        <td className={shop.varianceAmount ? (varianceVal >= 0 ? "targets-kpi-positive" : "targets-kpi-negative") : ""}>
                          {shop.varianceAmount ? <MoneyDisplay amount={shop.varianceAmount} /> : "—"}
                        </td>
                        <td>{shop.achievementPercent ? `${shop.achievementPercent}%` : "—"}</td>
                        <td><StateChip state={chip} label={label} /></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {/* Monthly Report View */}
      {activeTab === "monthly" && monthlyReport && (
        <>
          {/* Summary KPIs */}
          <div className="targets-kpi-grid">
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.gross").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={monthlyReport.summary.totalGrossSales} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.net").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={monthlyReport.summary.totalNetSales} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.tax").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={monthlyReport.summary.totalTaxAmount} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.txns").text}</span>
              <span className="targets-kpi-value">
                {monthlyReport.summary.totalTransactions} ({t("reporting.shop_reports.col.atv").text}: <MoneyDisplay amount={monthlyReport.summary.averageTransactionValue} />)
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.items").text}</span>
              <span className="targets-kpi-value">{monthlyReport.summary.totalItemsSold}</span>
            </div>
            {monthlyReport.summary.totalTargetAmount && (
              <>
                <div className="targets-kpi-card">
                  <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.target").text}</span>
                  <span className="targets-kpi-value">
                    <MoneyDisplay amount={monthlyReport.summary.totalTargetAmount} />
                  </span>
                </div>
                <div className="targets-kpi-card">
                  <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.variance").text}</span>
                  <span
                    className={`targets-kpi-value ${
                      parseFloat(monthlyReport.summary.totalVarianceAmount || "0") >= 0
                        ? "targets-kpi-positive"
                        : "targets-kpi-negative"
                    }`}
                  >
                    <MoneyDisplay amount={monthlyReport.summary.totalVarianceAmount || "0"} />
                  </span>
                </div>
                <div className="targets-kpi-card">
                  <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.achievement").text}</span>
                  <span className="targets-kpi-value">
                    {monthlyReport.summary.overallAchievementPercent
                      ? `${monthlyReport.summary.overallAchievementPercent}%`
                      : "—"}
                  </span>
                </div>
              </>
            )}
          </div>

          {/* Section 1: Cross-Shop Performance Comparison */}
          <h2>{t("reporting.shop_reports.cross_shop.title").text}</h2>
          {monthlyReport.shops.length === 0 ? (
            <p>{t("reporting.shop_reports.empty").text}</p>
          ) : (
            <div style={{ overflowX: "auto", marginBottom: "var(--space-4)" }}>
              <table className="reporting-data-table" aria-label={t("reporting.shop_reports.cross_shop.title").text}>
                <thead>
                  <tr>
                    <th>{t("reporting.shop_reports.col.code").text}</th>
                    <th>{t("reporting.shop_reports.col.name").text}</th>
                    <th>{t("reporting.shop_reports.col.gross").text}</th>
                    <th>{t("reporting.shop_reports.col.net").text}</th>
                    <th>{t("reporting.shop_reports.col.tax").text}</th>
                    <th>{t("reporting.shop_reports.col.txns").text}</th>
                    <th>{t("reporting.shop_reports.col.atv").text}</th>
                    <th>{t("reporting.shop_reports.col.items").text}</th>
                    <th>{t("reporting.shop_reports.col.target").text}</th>
                    <th>{t("reporting.shop_reports.col.variance").text}</th>
                    <th>{t("reporting.shop_reports.col.achievement").text}</th>
                    <th>{t("reporting.shop_reports.col.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {monthlyReport.shops.map((shop) => {
                    const { chip, label } = resolveState(shop.performanceState);
                    const varianceVal = parseFloat(shop.varianceAmount || "0");
                    return (
                      <tr key={shop.locationId}>
                        <td>{shop.locationCode}</td>
                        <td>{shop.locationName}</td>
                        <td><MoneyDisplay amount={shop.grossSales} /></td>
                        <td><MoneyDisplay amount={shop.netSales} /></td>
                        <td><MoneyDisplay amount={shop.taxAmount} /></td>
                        <td>{shop.transactionCount}</td>
                        <td><MoneyDisplay amount={shop.averageTransactionValue} /></td>
                        <td>{shop.itemsSold}</td>
                        <td>{shop.targetAmount ? <MoneyDisplay amount={shop.targetAmount} /> : "—"}</td>
                        <td className={shop.varianceAmount ? (varianceVal >= 0 ? "targets-kpi-positive" : "targets-kpi-negative") : ""}>
                          {shop.varianceAmount ? <MoneyDisplay amount={shop.varianceAmount} /> : "—"}
                        </td>
                        <td>{shop.achievementPercent ? `${shop.achievementPercent}%` : "—"}</td>
                        <td><StateChip state={chip} label={label} /></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {/* Section 2: Daily Sales Trend across the Month */}
          <h2>{t("reporting.shop_reports.trend.title").text}</h2>
          <div style={{ overflowX: "auto" }}>
            <table className="reporting-data-table" aria-label={t("reporting.shop_reports.trend.title").text}>
              <thead>
                <tr>
                  <th>{t("reporting.shop_reports.trend.date").text}</th>
                  <th>{t("reporting.shop_reports.col.gross").text}</th>
                  <th>{t("reporting.shop_reports.col.net").text}</th>
                  <th>{t("reporting.shop_reports.col.tax").text}</th>
                  <th>{t("reporting.shop_reports.col.txns").text}</th>
                  <th>{t("reporting.shop_reports.col.atv").text}</th>
                  <th>{t("reporting.shop_reports.col.items").text}</th>
                </tr>
              </thead>
              <tbody>
                {monthlyReport.dailyTrend.map((day) => (
                  <tr key={day.businessDate}>
                    <td>{formatDate(day.businessDate)}</td>
                    <td><MoneyDisplay amount={day.grossSales} /></td>
                    <td><MoneyDisplay amount={day.netSales} /></td>
                    <td><MoneyDisplay amount={day.taxAmount} /></td>
                    <td>{day.transactionCount}</td>
                    <td><MoneyDisplay amount={day.averageTransactionValue} /></td>
                    <td>{day.itemsSold}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </main>
  );
}
