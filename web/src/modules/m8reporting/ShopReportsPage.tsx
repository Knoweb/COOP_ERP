import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import {
  useReportingApi,
  type DailyShopReportItem,
  type DailyShopReportSummary,
  type MonthlyShopReportItem,
  type MonthlyShopReportSummary
} from "./reportingApi";
import { errorText } from "./reportView";
import { ShopReportDatePicker, ShopReportMonthPicker } from "./ShopReportDatePicker";
import { ShopReportSelect } from "./ShopReportSelect";
import "./reporting.css";

type KpiSummary = DailyShopReportSummary | MonthlyShopReportSummary;
type BreakdownRow = DailyShopReportItem | MonthlyShopReportItem;

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

  const shopOptions = [
    { value: "", label: t("reporting.shop_reports.all_shops").text },
    ...(locationsQuery.data ?? []).map((loc) => ({
      value: loc.locationId,
      label: locationText(loc, intl.locale)
    }))
  ];

  return (
    <main className="shell-page shop-reports-page">
      <PageHeader icon="society" title={t("reporting.shop_reports.title").text} />
      <p className="shop-reports-subtitle">{t("reporting.shop_reports.subtitle").text}</p>

      <div className="shop-reports-tablist" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "daily"}
          className={`shop-reports-tab ${activeTab === "daily" ? "shop-reports-tab--active" : ""}`}
          onClick={() => setActiveTab("daily")}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <rect x="3" y="4" width="18" height="18" rx="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </svg>
          <span>{t("reporting.shop_reports.tab.daily").text}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "monthly"}
          className={`shop-reports-tab ${activeTab === "monthly" ? "shop-reports-tab--active" : ""}`}
          onClick={() => setActiveTab("monthly")}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <rect x="3" y="12" width="4" height="8" rx="1" />
            <rect x="10" y="7" width="4" height="13" rx="1" />
            <rect x="17" y="3" width="4" height="17" rx="1" />
          </svg>
          <span>{t("reporting.shop_reports.tab.monthly").text}</span>
        </button>
      </div>

      <section className="modern-filter-panel shop-reports-filter-panel">
        <div className="shop-reports-field shop-reports-field--shop">
          <label htmlFor="shop-report-location" className="shop-reports-field__label">
            {t("reporting.shop_reports.shop").text}
          </label>
          <ShopReportSelect
            id="shop-report-location"
            ariaLabel={t("reporting.shop_reports.shop").text}
            value={filterLocation}
            onChange={setFilterLocation}
            options={shopOptions}
          />
        </div>

        {activeTab === "daily" && (
          <div className="shop-reports-field shop-reports-field--period">
            <label htmlFor="shop-report-date" className="shop-reports-field__label">
              {t("reporting.shop_reports.date").text}
            </label>
            <ShopReportDatePicker
              id="shop-report-date"
              ariaLabel={t("reporting.shop_reports.date").text}
              value={filterDate}
              onChange={setFilterDate}
            />
          </div>
        )}

        {activeTab === "monthly" && (
          <div className="shop-reports-field shop-reports-field--period">
            <label htmlFor="shop-report-month" className="shop-reports-field__label">
              {t("reporting.shop_reports.month").text}
            </label>
            <ShopReportMonthPicker
              id="shop-report-month"
              ariaLabel={t("reporting.shop_reports.month").text}
              value={filterYearMonth}
              onChange={setFilterYearMonth}
            />
          </div>
        )}
      </section>

      {isLoading && <p className="shop-reports-state">{t("reporting.loading").text}</p>}
      {isError && (
        <p role="alert" className="shop-reports-error">
          {errorText(error, t("reporting.error.generic").text)}
        </p>
      )}

      {activeTab === "daily" && dailyReport && (
        <>
          <ShopSummaryKpis summary={dailyReport.summary} />
          <ShopBreakdownSection
            rows={dailyReport.shops}
            tableAriaLabel={t("reporting.shop_reports.tab.daily").text}
            heading={t("reporting.shop_reports.cross_shop.title").text}
            resolveState={resolveState}
          />
        </>
      )}

      {activeTab === "monthly" && monthlyReport && (
        <>
          <ShopSummaryKpis summary={monthlyReport.summary} />
          <ShopBreakdownSection
            rows={monthlyReport.shops}
            tableAriaLabel={t("reporting.shop_reports.cross_shop.title").text}
            heading={t("reporting.shop_reports.cross_shop.title").text}
            resolveState={resolveState}
          />

          <section className="shop-reports-section">
            <div className="shop-reports-section__header">
              <div className="shop-reports-section__title-group">
                <span className="shop-reports-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
                    <polyline points="17 6 23 6 23 12" />
                  </svg>
                </span>
                <h2>{t("reporting.shop_reports.trend.title").text}</h2>
              </div>
              <span className="shop-reports-count-pill">{monthlyReport.dailyTrend.length}</span>
            </div>
            {monthlyReport.dailyTrend.length === 0 ? (
              <div className="shop-reports-empty-box">
                <p>{t("reporting.shop_reports.empty").text}</p>
              </div>
            ) : (
              <div className="modern-table-card shop-reports-table-card">
                <div className="modern-table-scroll">
                  <table
                    className="modern-table reporting-data-table shop-reports-table"
                    aria-label={t("reporting.shop_reports.trend.title").text}
                  >
                    <thead>
                      <tr>
                        <th scope="col">{t("reporting.shop_reports.trend.date").text}</th>
                        <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.gross").text}</th>
                        <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.net").text}</th>
                        <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.tax").text}</th>
                        <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.txns").text}</th>
                        <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.atv").text}</th>
                        <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.items").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {monthlyReport.dailyTrend.map((day) => (
                        <tr key={day.businessDate}>
                          <td className="shop-reports-date-cell">{formatDate(day.businessDate)}</td>
                          <td className="shop-reports-num"><MoneyDisplay amount={day.grossSales} /></td>
                          <td className="shop-reports-num"><MoneyDisplay amount={day.netSales} /></td>
                          <td className="shop-reports-num"><MoneyDisplay amount={day.taxAmount} /></td>
                          <td className="shop-reports-num">{day.transactionCount}</td>
                          <td className="shop-reports-num"><MoneyDisplay amount={day.averageTransactionValue} /></td>
                          <td className="shop-reports-num">{day.itemsSold}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </section>
        </>
      )}
    </main>
  );
}

function ShopSummaryKpis({ summary }: { summary: KpiSummary }) {
  const t = useT();
  const varianceVal = parseFloat(summary.totalVarianceAmount || "0");
  const varianceTone = varianceVal >= 0 ? "success" : "alert";

  return (
    <div className={`shop-reports-kpi-grid ${summary.totalTargetAmount ? "shop-reports-kpi-grid--with-targets" : ""}`.trim()}>
      <div className="shop-reports-kpi-card shop-reports-kpi-card--primary">
        <div className="shop-reports-kpi-card__top">
          <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.gross").text}</span>
          <span className="shop-reports-kpi-badge" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
              <polyline points="17 6 23 6 23 12" />
            </svg>
          </span>
        </div>
        <div className="shop-reports-kpi-card__bottom">
          <span className="shop-reports-kpi-value">
            <MoneyDisplay amount={summary.totalGrossSales} />
          </span>
        </div>
      </div>

      <div className="shop-reports-kpi-card shop-reports-kpi-card--success">
        <div className="shop-reports-kpi-card__top">
          <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.net").text}</span>
          <span className="shop-reports-kpi-badge" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <rect x="2" y="5" width="20" height="14" rx="2" />
              <line x1="2" y1="10" x2="22" y2="10" />
            </svg>
          </span>
        </div>
        <div className="shop-reports-kpi-card__bottom">
          <span className="shop-reports-kpi-value">
            <MoneyDisplay amount={summary.totalNetSales} />
          </span>
        </div>
      </div>

      <div className="shop-reports-kpi-card shop-reports-kpi-card--navy">
        <div className="shop-reports-kpi-card__top">
          <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.tax").text}</span>
          <span className="shop-reports-kpi-badge" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <line x1="19" y1="5" x2="5" y2="19" />
              <circle cx="6.5" cy="6.5" r="2.5" />
              <circle cx="17.5" cy="17.5" r="2.5" />
            </svg>
          </span>
        </div>
        <div className="shop-reports-kpi-card__bottom">
          <span className="shop-reports-kpi-value">
            <MoneyDisplay amount={summary.totalTaxAmount} />
          </span>
        </div>
      </div>

      <div className="shop-reports-kpi-card shop-reports-kpi-card--lavender">
        <div className="shop-reports-kpi-card__top">
          <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.txns").text}</span>
          <span className="shop-reports-kpi-badge" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <path d="M14 2v6h6" />
              <path d="M8 13h8" />
              <path d="M8 17h5" />
            </svg>
          </span>
        </div>
        <div className="shop-reports-kpi-card__bottom">
          <span className="shop-reports-kpi-value">{summary.totalTransactions}</span>
          <span className="shop-reports-kpi-sub">
            <span className="shop-reports-kpi-sub__label">{t("reporting.shop_reports.col.atv").text}</span>
            <strong className="shop-reports-kpi-sub__val">
              <MoneyDisplay amount={summary.averageTransactionValue} />
            </strong>
          </span>
        </div>
      </div>

      <div className="shop-reports-kpi-card shop-reports-kpi-card--primary">
        <div className="shop-reports-kpi-card__top">
          <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.items").text}</span>
          <span className="shop-reports-kpi-badge" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
              <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
              <line x1="12" y1="22.08" x2="12" y2="12" />
            </svg>
          </span>
        </div>
        <div className="shop-reports-kpi-card__bottom">
          <span className="shop-reports-kpi-value">{summary.totalItemsSold}</span>
        </div>
      </div>

      {summary.totalTargetAmount && (
        <>
          <div className="shop-reports-kpi-card shop-reports-kpi-card--navy">
            <div className="shop-reports-kpi-card__top">
              <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.target").text}</span>
              <span className="shop-reports-kpi-badge" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <circle cx="12" cy="12" r="10" />
                  <circle cx="12" cy="12" r="6" />
                  <circle cx="12" cy="12" r="2" />
                </svg>
              </span>
            </div>
            <div className="shop-reports-kpi-card__bottom">
              <span className="shop-reports-kpi-value">
                <MoneyDisplay amount={summary.totalTargetAmount} />
              </span>
            </div>
          </div>

          <div className={`shop-reports-kpi-card shop-reports-kpi-card--${varianceTone}`}>
            <div className="shop-reports-kpi-card__top">
              <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.variance").text}</span>
              <span className="shop-reports-kpi-badge" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="M12 2v20" />
                  <path d="m17 5-5-3-5 3" />
                  <path d="m17 19-5 3-5-3" />
                </svg>
              </span>
            </div>
            <div className="shop-reports-kpi-card__bottom">
              <span className={`shop-reports-kpi-value ${varianceVal >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"}`}>
                <MoneyDisplay amount={summary.totalVarianceAmount || "0"} />
              </span>
            </div>
          </div>

          <div className="shop-reports-kpi-card shop-reports-kpi-card--primary">
            <div className="shop-reports-kpi-card__top">
              <span className="shop-reports-kpi-label">{t("reporting.shop_reports.kpi.achievement").text}</span>
              <span className="shop-reports-kpi-badge" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              </span>
            </div>
            <div className="shop-reports-kpi-card__bottom">
              <span className="shop-reports-kpi-value">
                {summary.overallAchievementPercent ? `${summary.overallAchievementPercent}%` : "—"}
              </span>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function ShopBreakdownSection({
  rows,
  tableAriaLabel,
  heading,
  resolveState
}: {
  rows: BreakdownRow[];
  tableAriaLabel: string;
  heading: string;
  resolveState: (state: "TARGET_MET" | "BEHIND_TARGET" | "NO_TARGET") => { chip: ChipState; label: string };
}) {
  const t = useT();

  return (
    <section className="shop-reports-section">
      <div className="shop-reports-section__header">
        <div className="shop-reports-section__title-group">
          <span className="shop-reports-section__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
              <polyline points="9 22 9 12 15 12 15 22" />
            </svg>
          </span>
          <h2>{heading}</h2>
        </div>
        <span className="shop-reports-count-pill">{rows.length}</span>
      </div>

      {rows.length === 0 ? (
        <div className="shop-reports-empty-box">
          <p>{t("reporting.shop_reports.empty").text}</p>
        </div>
      ) : (
        <div className="modern-table-card shop-reports-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table reporting-data-table shop-reports-table" aria-label={tableAriaLabel}>
              <thead>
                <tr>
                  <th scope="col">{t("reporting.shop_reports.col.code").text}</th>
                  <th scope="col">{t("reporting.shop_reports.col.name").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.gross").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.net").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.tax").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.txns").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.atv").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.items").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.target").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.variance").text}</th>
                  <th scope="col" className="shop-reports-num">{t("reporting.shop_reports.col.achievement").text}</th>
                  <th scope="col">{t("reporting.shop_reports.col.status").text}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((shop) => {
                  const { chip, label } = resolveState(shop.performanceState);
                  const varianceVal = parseFloat(shop.varianceAmount || "0");
                  return (
                    <tr key={shop.locationId}>
                      <td>
                        <span className="shop-reports-code-pill">{shop.locationCode}</span>
                      </td>
                      <td className="shop-reports-name-cell">{shop.locationName}</td>
                      <td className="shop-reports-num"><MoneyDisplay amount={shop.grossSales} /></td>
                      <td className="shop-reports-num"><MoneyDisplay amount={shop.netSales} /></td>
                      <td className="shop-reports-num"><MoneyDisplay amount={shop.taxAmount} /></td>
                      <td className="shop-reports-num">{shop.transactionCount}</td>
                      <td className="shop-reports-num"><MoneyDisplay amount={shop.averageTransactionValue} /></td>
                      <td className="shop-reports-num">{shop.itemsSold}</td>
                      <td className="shop-reports-num">{shop.targetAmount ? <MoneyDisplay amount={shop.targetAmount} /> : "—"}</td>
                      <td className={`shop-reports-num ${shop.varianceAmount ? (varianceVal >= 0 ? "targets-kpi-positive" : "targets-kpi-negative") : ""}`}>
                        {shop.varianceAmount ? <MoneyDisplay amount={shop.varianceAmount} /> : "—"}
                      </td>
                      <td className="shop-reports-num">{shop.achievementPercent ? `${shop.achievementPercent}%` : "—"}</td>
                      <td><StateChip state={chip} label={label} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}
