import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { useReportingApi, type Tile } from "./reportingApi";
import { errorText, tileLink, trendHeights } from "./reportView";
import { ExceptionList } from "./ExceptionList";
import { ShopTargetsDashboardCard } from "./ShopTargetsDashboardCard";
import { ShopReportsDashboardCard } from "./ShopReportsDashboardCard";
import { useHasPermission } from "../../shell/auth/permissions";
import "./reporting.css";

/**
 * The dashboard (28A section 8): the tiles the server has for the caller's scope (they are data,
 * seed/m8reporting/dashboard-tiles.yaml), each with its value, an eight-week trend where it has
 * one, and a link to its report; then the exception queue and the reports. Nothing is added up
 * here: every figure is the server's.
 */
export function DashboardPage() {
  const t = useT();
  const api = useReportingApi();
  const formatInstant = useFormatInstant();
  const canViewTargets = useHasPermission("rpt.target.view");
  const dashboard = useQuery({ queryKey: ["reporting", "dashboard"], queryFn: () => api.dashboard() });
  const definitions = useQuery({
    queryKey: ["reporting", "definitions"],
    queryFn: () => api.definitions(),
    staleTime: Infinity
  });

  const freshnessBadge = dashboard.data ? (
    <p className="dashboard-freshness-pill">
      <svg className="dashboard-freshness-pill__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </svg>
      <span>
        {dashboard.data.freshness
          ? t("reporting.freshness", undefined, { when: formatInstant(dashboard.data.freshness) }).text
          : t("reporting.freshness.none").text}
      </span>
    </p>
  ) : null;

  return (
    <main className="shell-page dashboard-page">
      <PageHeader
        icon={
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <rect x="3" y="13" width="4" height="8" rx="1" />
            <rect x="10" y="9" width="4" height="12" rx="1" />
            <rect x="17" y="4" width="4" height="17" rx="1" />
          </svg>
        }
        title={t("reporting.dashboard.title").text}
        actions={freshnessBadge}
      />

      {dashboard.isLoading && <p className="dashboard-status-note">{t("reporting.loading").text}</p>}
      {dashboard.isError && <p role="alert">{errorText(dashboard.error, t("reporting.error.generic").text)}</p>}
      {dashboard.data && (
        <ul className="modern-dashboard-tiles dashboard-tiles">
          {dashboard.data.tiles.map((tile) => (
            <TileCard key={tile.tileId} tile={tile} />
          ))}
        </ul>
      )}

      <ShopReportsDashboardCard />

      {canViewTargets && <ShopTargetsDashboardCard />}

      <section className="dashboard-section">
        <div className="dashboard-section__header">
          <h2>{t("reporting.exceptions.title").text}</h2>
          <Link className="dashboard-section__link" to="/reporting/exceptions">
            <span>{t("reporting.exceptions.open").text}</span>
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M5 12h14" />
              <path d="m12 5 7 7-7 7" />
            </svg>
          </Link>
        </div>
        <ExceptionList />
      </section>

      <section className="dashboard-section">
        <div className="dashboard-section__header">
          <h2>{t("reporting.reports.title").text}</h2>
        </div>
        {definitions.data && (
          <ul className="dashboard-reports-grid">
            {definitions.data.map((definition) => (
              <li key={definition.reportId} className="dashboard-report-card">
                <div className="dashboard-report-card__top">
                  <span className="dashboard-report-card__icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" focusable="false">
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                      <path d="M14 2v6h6" />
                      <path d="M8 13h8" />
                      <path d="M8 17h5" />
                    </svg>
                  </span>
                  <h3 className="dashboard-report-card__title">
                    <Link className="dashboard-report-card__link" to={`/reporting/reports/${definition.reportId}`}>
                      {t(definition.titleId).text}
                    </Link>
                  </h3>
                </div>
                <p className="dashboard-report-card__desc">{t(definition.decisionId).text}</p>
                <span className="dashboard-report-card__cta" aria-hidden="true">
                  <span>{t("reporting.dashboard.open").text}</span>
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M5 12h14" />
                    <path d="m12 5 7 7-7 7" />
                  </svg>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

function tileTone(tile: Tile): "primary" | "info" | "success" | "alert" {
  const id = `${tile.tileId} ${tile.drillReportId ?? ""}`.toLowerCase();
  if (id.includes("overdue") || id.includes("exception") || id.includes("exposure")) {
    return "alert";
  }
  if (id.includes("receivable") || id.includes("stock") || id.includes("grn") || id.includes("fill")) {
    return "success";
  }
  if (id.includes("purchase") || id.includes("payable") || id.includes("order") || id.includes("deliver")) {
    return "info";
  }
  return "primary";
}

function TileIcon({ tone }: { tone: "primary" | "info" | "success" | "alert" }) {
  if (tone === "alert") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </svg>
    );
  }
  if (tone === "success") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <rect x="2" y="5" width="20" height="14" rx="2" />
        <line x1="2" y1="10" x2="22" y2="10" />
      </svg>
    );
  }
  if (tone === "info") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
        <line x1="12" y1="22.08" x2="12" y2="12" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
      <polyline points="17 6 23 6 23 12" />
    </svg>
  );
}

function TileCard({ tile }: { tile: Tile }) {
  const t = useT();
  const link = tileLink(tile.drillReportId);
  const label = t(tile.labelId).text;
  const tone = tileTone(tile);
  return (
    <li className={`modern-dashboard-tile dashboard-tile dashboard-tile--${tone}`} aria-label={label}>
      <div className="dashboard-tile__head">
        <span className="modern-dashboard-tile__label dashboard-tile__label">{label}</span>
        <span className={`dashboard-tile__badge dashboard-tile__badge--${tone}`} aria-hidden="true">
          <TileIcon tone={tone} />
        </span>
      </div>
      <span className="modern-dashboard-tile__value dashboard-tile__value">
        <TileValue kind={tile.kind} value={tile.value} />
      </span>
      {tile.trend && tile.trend.length > 0 && (
        <div className="dashboard-trend-panel">
          <Trend points={tile.trend} />
        </div>
      )}
      {link && (
        <Link className="modern-dashboard-tile__link dashboard-tile__link" to={link}>
          <span>
            {tile.drillReportId === "exceptions" ? t("reporting.exceptions.open").text : t("reporting.dashboard.open").text}
          </span>
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M5 12h14" />
            <path d="m12 5 7 7-7 7" />
          </svg>
        </Link>
      )}
    </li>
  );
}

function TileValue({ kind, value }: { kind: string; value: string }) {
  const t = useT();
  if (kind === "MONEY") {
    return <MoneyDisplay amount={value} />;
  }
  if (kind === "PERCENT") {
    return <>{t("reporting.percent", undefined, { value }).text}</>;
  }
  return <>{value}</>;
}

/**
 * The eight weeks ending today as small bars, one series in the accent colour. Each bar says its
 * week and figure on hover (title) and to a screen reader (the list's labels), so the trend is
 * never a picture only.
 */
function Trend({ points }: { points: NonNullable<Tile["trend"]> }) {
  const t = useT();
  const formatDate = useFormatDate();
  const heights = trendHeights(points.map((point) => point.value));
  const width = 100 / points.length;
  return (
    <svg
      className="reporting-trend"
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      role="img"
      aria-label={t("reporting.trend.label").text}
    >
      {points.map((point, index) => {
        const text = t("reporting.trend.week", undefined, {
          from: formatDate(point.from),
          value: point.value
        }).text;
        const barX = index * width + 1.75;
        const barWidth = width - 3.5;
        const height = heights[index];
        return (
          <g key={point.from}>
            <rect
              className="reporting-trend__track"
              x={barX}
              y={8}
              width={barWidth}
              height={88}
              rx="2.5"
            />
            {height > 0 && (
              <rect
                className="reporting-trend__bar"
                x={barX}
                y={96 - Math.max(height * 0.88, 6)}
                width={barWidth}
                height={Math.max(height * 0.88, 6)}
                rx="2.5"
              >
                <title>{text}</title>
              </rect>
            )}
            {height === 0 && (
              <rect
                className="reporting-trend__zero"
                x={barX}
                y={92}
                width={barWidth}
                height={4}
                rx="2"
              >
                <title>{text}</title>
              </rect>
            )}
          </g>
        );
      })}
    </svg>
  );
}
