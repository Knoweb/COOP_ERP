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
          <ul
            style={{
              listStyle: "none",
              padding: 0,
              display: "grid",
              gap: "var(--space-2)",
              gridTemplateColumns: "repeat(auto-fill, minmax(calc(var(--space-8) * 3), 1fr))"
            }}
          >
            {dashboard.data.tiles.map((tile) => (
              <li
                key={tile.tileId}
                style={{
                  border: "var(--border-width) solid var(--color-border)",
                  padding: "var(--space-2)",
                  display: "grid",
                  gap: "var(--space-1)"
                }}
              >
                <span>{t(tile.labelId).text}</span>
                <strong style={{ fontSize: "var(--font-size-xl)" }}>
                  {tile.kind === "MONEY" ? <MoneyDisplay amount={tile.value} /> : tile.value}
                </strong>
                {tile.drillReportId && (
                  <Link to={`/reporting/reports/${tile.drillReportId}`}>{t("reporting.dashboard.open").text}</Link>
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
