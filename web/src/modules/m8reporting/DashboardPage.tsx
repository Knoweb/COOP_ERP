import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useReportingApi, type Tile } from "./reportingApi";
import { errorText, tileLink, trendHeights } from "./reportView";
import { ExceptionList } from "./ExceptionList";
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
  const dashboard = useQuery({ queryKey: ["reporting", "dashboard"], queryFn: () => api.dashboard() });
  const definitions = useQuery({
    queryKey: ["reporting", "definitions"],
    queryFn: () => api.definitions(),
    staleTime: Infinity
  });

  return (
    <main className="shell-page">
      <h1>{t("reporting.dashboard.title").text}</h1>
      {dashboard.isLoading && <p>{t("reporting.loading").text}</p>}
      {dashboard.isError && <p role="alert">{errorText(dashboard.error, t("reporting.error.generic").text)}</p>}
      {dashboard.data && (
        <>
          <p>
            {dashboard.data.freshness
              ? t("reporting.freshness", undefined, { when: formatInstant(dashboard.data.freshness) }).text
              : t("reporting.freshness.none").text}
          </p>
          <ul className="modern-dashboard-tiles">
            {dashboard.data.tiles.map((tile) => (
              <TileCard key={tile.tileId} tile={tile} />
            ))}
          </ul>
        </>
      )}

      <h2>{t("reporting.exceptions.title").text}</h2>
      <ExceptionList />

      <h2>{t("reporting.reports.title").text}</h2>
      {definitions.data && (
        <ul>
          {definitions.data.map((definition) => (
            <li key={definition.reportId}>
              <Link to={`/reporting/reports/${definition.reportId}`}>{t(definition.titleId).text}</Link>
              {" — "}
              {t(definition.decisionId).text}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}

function TileCard({ tile }: { tile: Tile }) {
  const t = useT();
  const link = tileLink(tile.drillReportId);
  const label = t(tile.labelId).text;
  return (
    <li className="modern-dashboard-tile" aria-label={label}>
      <span className="modern-dashboard-tile__label">{label}</span>
      <span className="modern-dashboard-tile__value">
        <TileValue kind={tile.kind} value={tile.value} />
      </span>
      {tile.trend && tile.trend.length > 0 && <Trend points={tile.trend} />}
      {link && (
        <Link className="modern-dashboard-tile__link" to={link}>
          {tile.drillReportId === "exceptions" ? t("reporting.exceptions.open").text : t("reporting.dashboard.open").text}
          <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
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
        const height = Math.max(heights[index], 2);
        return (
          <rect key={point.from} x={index * width + 1} y={100 - height} width={width - 2} height={height} rx="1">
            <title>{text}</title>
          </rect>
        );
      })}
    </svg>
  );
}
