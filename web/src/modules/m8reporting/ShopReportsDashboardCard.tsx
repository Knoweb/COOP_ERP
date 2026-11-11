import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useReportingApi } from "./reportingApi";
import { errorText } from "./reportView";

export function ShopReportsDashboardCard() {
  const t = useT();
  const api = useReportingApi();
  const dailyReport = useQuery({
    queryKey: ["reporting", "daily-shop-report", "today"],
    queryFn: () => api.dailyShopReport()
  });

  if (dailyReport.isLoading) {
    return <p>{t("reporting.loading").text}</p>;
  }

  if (dailyReport.isError) {
    return <p role="alert">{errorText(dailyReport.error, t("reporting.error.generic").text)}</p>;
  }

  const summary = dailyReport.data?.summary;
  if (!summary) {
    return null;
  }

  return (
    <section className="shop-targets-card">
      <div className="shop-targets-card__header">
        <div className="shop-targets-card__title-group">
          <span className="shop-targets-card__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
              <polyline points="9 22 9 12 15 12 15 22" />
            </svg>
          </span>
          <div>
            <h3>{t("reporting.shop_reports.card.title").text}</h3>
            <p className="shop-targets-card__subtitle">{t("reporting.shop_reports.card.subtitle").text}</p>
          </div>
        </div>
        <Link className="modern-dashboard-tile__link dashboard-section__link" to="/reporting/shop-reports">
          <span>{t("reporting.shop_reports.card.view_all").text}</span>
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M5 12h14" />
            <path d="m12 5 7 7-7 7" />
          </svg>
        </Link>
      </div>

      <div className="targets-kpi-grid">
        <div className="targets-kpi-card targets-kpi-card--money">
          <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.gross").text}</span>
          <span className="targets-kpi-value"><MoneyDisplay amount={summary.totalGrossSales} /></span>
        </div>
        <div className="targets-kpi-card targets-kpi-card--money">
          <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.net").text}</span>
          <span className="targets-kpi-value"><MoneyDisplay amount={summary.totalNetSales} /></span>
        </div>
        <div className="targets-kpi-card">
          <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.txns").text}</span>
          <span className="targets-kpi-value">{summary.totalTransactions}</span>
        </div>
        <div className="targets-kpi-card">
          <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.items").text}</span>
          <span className="targets-kpi-value">{summary.totalItemsSold}</span>
        </div>
        {summary.totalTargetAmount && (
          <div className="targets-kpi-card">
            <span className="targets-kpi-label">{t("reporting.shop_reports.kpi.achievement").text}</span>
            <span className="targets-kpi-value">
              {summary.overallAchievementPercent ? `${summary.overallAchievementPercent}%` : "—"}
            </span>
          </div>
        )}
      </div>
    </section>
  );
}
