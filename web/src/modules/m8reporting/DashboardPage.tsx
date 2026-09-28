import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useReportingApi } from "./reportingApi";
import { errorText } from "./reportView";

/**
 * The dashboard (28A section 8, demo scope): a handful of counts for the caller's scope, each a
 * tile with its value, and the reports. A tile that has a report opens it. The stock value is a
 * cost and only comes for the owner's users and the Federation view.
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
              <li key={tile.tileId} className="modern-dashboard-tile">
                <span className="modern-dashboard-tile__label">{t(tile.labelId).text}</span>
                <span className="modern-dashboard-tile__value">
                  {tile.kind === "MONEY" ? <MoneyDisplay amount={tile.value} /> : tile.value}
                </span>
                {tile.drillReportId && (
                  <Link className="modern-dashboard-tile__link" to={`/reporting/reports/${tile.drillReportId}`}>
                    {t("reporting.dashboard.open").text}
                    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
                  </Link>
                )}
              </li>
            ))}
          </ul>
        </>
      )}

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
