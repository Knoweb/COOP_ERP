import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { openServerFile } from "../../shell/api/openServerFile";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useReportingApi, type ReportQuery, type ReportRun } from "./reportingApi";
import { csvFileName, defaultPeriod, errorText, isNumeric, queryOf } from "./reportView";
import "./reporting.css";

/** How often a print run is asked for while the worker prints it. */
const RUN_POLL_MS = 2000;

/**
 * One report (28A section 8, "Report viewer", demo scope): its parameters, its rows with the
 * totals the server added, and, with rpt.export.run, the CSV and the A4 print. The print is a
 * run the worker renders; the page asks for it until it is ready and then offers the PDF.
 * Amounts go through MoneyDisplay; the page adds nothing up itself.
 */
export function ReportPage() {
  const { reportId = "" } = useParams();
  const t = useT();
  const intl = useIntl();
  const api = useReportingApi();
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const canExport = useHasPermission("rpt.export.run");
  const runKey = useIdempotencyKey();

  const definitions = useQuery({
    queryKey: ["reporting", "definitions"],
    queryFn: () => api.definitions(),
    staleTime: Infinity
  });
  const definition = definitions.data?.find((d) => d.reportId === reportId);
  const locations = useQuery({
    queryKey: ["reporting", "locations"],
    queryFn: () => api.locations(),
    staleTime: Infinity,
    enabled: definition?.location === true
  });

  const [form, setForm] = useState({ ...defaultPeriod(new Date()), locationId: "" });
  const [query, setQuery] = useState<ReportQuery | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const [run, setRun] = useState<ReportRun | null>(null);

  // A report without parameters shows at once; a period report waits for "Show".
  useEffect(() => {
    if (definition && !definition.period && query === null) {
      setQuery(queryOf(definition, form));
    }
  }, [definition, form, query]);

  // The run history: the entity's earlier prints of this report, asked again after each print.
  const runs = useQuery({
    queryKey: ["reporting", "runs", reportId, run?.runId, run?.status],
    queryFn: () => api.runs(reportId),
    enabled: canExport && definition !== undefined
  });

  const data = useQuery({
    queryKey: ["reporting", "data", reportId, query],
    queryFn: () => api.data(reportId, query!),
    enabled: query !== null
  });

  // While the worker prints, ask again every few seconds.
  useEffect(() => {
    if (!run || run.status !== "REQUESTED") {
      return;
    }
    const timer = window.setTimeout(() => {
      api.run(run.runId).then(setRun, (error) => setExportError(errorText(error, t("reporting.error.generic").text)));
    }, RUN_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [run, api, t]);

  if (definitions.isLoading) {
    return <main className="shell-page">{t("reporting.loading").text}</main>;
  }
  if (!definition) {
    return (
      <main className="shell-page">
        <p role="alert">{t("reporting.report.unknown").text}</p>
        <Link className="back-link" to="/reporting">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("reporting.back").text}
      </Link>
      </main>
    );
  }

  const current = queryOf(definition, form);

  async function downloadCsv() {
    setExportError(null);
    try {
      const text = await api.csv(reportId, current);
      const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = csvFileName(reportId, current);
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      setExportError(errorText(error, t("reporting.error.generic").text));
    }
  }

  async function print() {
    setExportError(null);
    try {
      const language = (["en", "si", "ta"].includes(intl.locale) ? intl.locale : "en") as "en" | "si" | "ta";
      setRun(await api.requestRun(reportId, current, language, runKey.current()));
      runKey.next();
    } catch (error) {
      setExportError(errorText(error, t("reporting.error.generic").text));
    }
  }

  return (
    <main className="shell-page">
      <p>
        <Link className="back-link" to="/reporting">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("reporting.back").text}
      </Link>
      </p>
      <h1>{t(definition.titleId).text}</h1>
      <p>{t(definition.decisionId).text}</p>

      <form
        className="modern-filter-panel reporting-filter-bar"
        onSubmit={(event) => {
          event.preventDefault();
          setQuery(current);
        }}
      >
        {definition.period && (
          <>
            <label className="modern-field">
              <span className="modern-field__label reporting-filter-label">{t("reporting.field.from").text}</span>
              <input
                type="date"
                value={form.from}
                required
                onChange={(event) => setForm({ ...form, from: event.target.value })}
              />
            </label>
            <label className="modern-field">
              <span className="modern-field__label reporting-filter-label">{t("reporting.field.to").text}</span>
              <input
                type="date"
                value={form.to}
                required
                onChange={(event) => setForm({ ...form, to: event.target.value })}
              />
            </label>
          </>
        )}
        {definition.location && (
          <label className="modern-field">
            <span className="modern-field__label reporting-filter-label">{t("reporting.field.location").text}</span>
            <div className="modern-select">
              <select value={form.locationId} onChange={(event) => setForm({ ...form, locationId: event.target.value })}>
                <option value="">{t("reporting.field.location.all").text}</option>
                {(locations.data ?? []).map((location) => (
                  <option key={location.locationId} value={location.locationId}>
                    {locationText(location, intl.locale)}
                  </option>
                ))}
              </select>
              <svg className="modern-select__arrow" viewBox="0 0 24 24"><path d="m7 9 5 5 5-5" /></svg>
            </div>
          </label>
        )}
        <button type="submit" className="modern-btn modern-btn--primary">{t("reporting.show").text}</button>
        {canExport && (
          <>
            <button type="button" className="modern-btn" onClick={downloadCsv}>
              {t("reporting.csv").text}
            </button>
            <button type="button" className="modern-btn" onClick={print} disabled={run?.status === "REQUESTED"}>
              {t("reporting.print").text}
            </button>
          </>
        )}
      </form>

      {exportError && <p role="alert">{exportError}</p>}
      {run && (
        <p role="status">
          {run.status === "REQUESTED" && t("reporting.print.waiting").text}
          {run.status === "READY" && run.downloadUrl && (
            <a href={run.downloadUrl} target="_blank" rel="noreferrer">
              {t("reporting.print.ready").text}
            </a>
          )}
          {run.status === "FAILED" && t("reporting.print.failed", undefined, { code: run.errorCode ?? "" }).text}
        </p>
      )}

      {data.isLoading && <p>{t("reporting.loading").text}</p>}
      {data.isError && <p role="alert">{errorText(data.error, t("reporting.error.generic").text)}</p>}
      {data.data && (
        <>
          <p>
            {data.data.freshness
              ? t("reporting.freshness", undefined, { when: formatInstant(data.data.freshness) }).text
              : t("reporting.freshness.none").text}
          </p>
          {data.data.rows.length === 0 ? (
            <p>{t("reporting.empty").text}</p>
          ) : (
            <div className="modern-table-card">
              <div className="modern-table-scroll">
                <table className="modern-table">
                  <thead>
                    <tr>
                      {data.data.columns.map((column) => (
                        <th key={column.key} style={isNumeric(column.kind) ? { textAlign: "right" } : undefined}>
                          {t(column.labelId).text}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.data.rows.map((row, index) => (
                      <tr key={index}>
                        {data.data.columns.map((column) => (
                          <td key={column.key} style={isNumeric(column.kind) ? { textAlign: "right" } : undefined}>
                            {cell(column.kind, row[column.key])}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      {data.data.columns.map((column, index) => (
                        <th key={column.key} style={isNumeric(column.kind) ? { textAlign: "right" } : undefined}>
                          {data.data.totals[column.key] !== undefined
                            ? cell(column.kind, data.data.totals[column.key])
                            : index === 0
                              ? t("reporting.total").text
                              : ""}
                        </th>
                      ))}
                    </tr>
                  </tfoot>
                </table>
              </div>
            </div>
          )}
        </>
      )}

      {canExport && runs.data && (
        <section>
          <h2>{t("reporting.runs.title").text}</h2>
          {runs.data.length === 0 ? (
            <p>{t("reporting.runs.empty").text}</p>
          ) : (
            <div className="modern-table-card">
              <div className="modern-table-scroll">
                <table className="modern-table">
                  <thead>
                    <tr>
                      <th scope="col">{t("reporting.runs.col.requested").text}</th>
                      <th scope="col">{t("reporting.runs.col.language").text}</th>
                      <th scope="col">{t("reporting.runs.col.status").text}</th>
                      <th scope="col" />
                    </tr>
                  </thead>
                  <tbody>
                    {runs.data.map((earlier) => (
                      <tr key={earlier.runId}>
                        <td>{formatInstant(earlier.requestedAt)}</td>
                        <td>{earlier.language}</td>
                        <td>{t(`reporting.runs.status.${earlier.status}`).text}</td>
                        <td>
                          {earlier.status === "READY" && (
                            <button
                              type="button"
                              className="modern-btn"
                              onClick={() =>
                                openServerFile(() => api.run(earlier.runId).then((fresh) => fresh.downloadUrl)).catch(
                                  (error) => setExportError(errorText(error, t("reporting.error.generic").text))
                                )
                              }
                            >
                              {t("reporting.runs.open").text}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>
      )}
    </main>
  );

  function cell(kind: string, value: string | undefined) {
    if (value === undefined || value === "") {
      return "";
    }
    if (kind === "MONEY") {
      return <MoneyDisplay amount={value} />;
    }
    if (kind === "DATE") {
      return formatDate(value);
    }
    if (kind === "PERCENT") {
      return t("reporting.percent", undefined, { value }).text;
    }
    return value;
  }
}
