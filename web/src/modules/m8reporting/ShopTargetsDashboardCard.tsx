import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useReportingApi } from "./reportingApi";
import { errorText } from "./reportView";

export function ShopTargetsDashboardCard() {
  const t = useT();
  const api = useReportingApi();
  const performance = useQuery({
    queryKey: ["reporting", "shop-targets-performance"],
    queryFn: () => api.getShopTargetPerformance()
  });

  if (performance.isLoading) {
    return <p>{t("reporting.loading").text}</p>;
  }

  if (performance.isError) {
    return <p role="alert">{errorText(performance.error, t("reporting.error.generic").text)}</p>;
  }

  const items = performance.data?.items ?? [];
  if (items.length === 0) {
    return null;
  }

  let totalTarget = 0;
  let totalActual = 0;
  for (const item of items) {
    totalTarget += parseFloat(item.targetAmount) || 0;
    totalActual += parseFloat(item.actualAmount) || 0;
  }
  const totalVariance = totalActual - totalTarget;
  const overallAchievement = totalTarget > 0 ? ((totalActual / totalTarget) * 100).toFixed(1) : null;

  return (
    <div className="shop-targets-card">
      <div className="shop-targets-card__header">
        <div>
          <h3>{t("reporting.targets.card.title").text}</h3>
          <p className="shop-targets-card__subtitle">{t("reporting.targets.subtitle").text}</p>
        </div>
        <Link className="modern-dashboard-tile__link" to="/reporting/targets">
          {t("reporting.targets.card.view_all").text}
          <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
        </Link>
      </div>

      <div className="targets-kpi-grid">
        <div className="targets-kpi-card">
          <span className="targets-kpi-label">{t("reporting.targets.kpi.total_target").text}</span>
          <span className="targets-kpi-value"><MoneyDisplay amount={totalTarget.toFixed(2)} /></span>
        </div>
        <div className="targets-kpi-card">
          <span className="targets-kpi-label">{t("reporting.targets.kpi.total_actual").text}</span>
          <span className="targets-kpi-value"><MoneyDisplay amount={totalActual.toFixed(2)} /></span>
        </div>
        <div className="targets-kpi-card">
          <span className="targets-kpi-label">{t("reporting.targets.kpi.total_variance").text}</span>
          <span className={`targets-kpi-value ${totalVariance >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"}`}>
            <MoneyDisplay amount={totalVariance.toFixed(2)} />
          </span>
        </div>
        <div className="targets-kpi-card">
          <span className="targets-kpi-label">{t("reporting.targets.kpi.overall_achievement").text}</span>
          <span className="targets-kpi-value">
            {overallAchievement !== null ? `${overallAchievement}%` : "—"}
          </span>
        </div>
      </div>
    </div>
  );
}
