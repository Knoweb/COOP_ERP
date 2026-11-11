import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { businessToday, useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { isOpenableFileUrl, openServerFile } from "../../shell/api/openServerFile";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { ReportingSelect } from "./ReportingSelect";
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

  const [form, setForm] = useState({ ...defaultPeriod(businessToday()), locationId: "" });
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
      <main className="shell-page report-viewer-page">
        <div className="report-back-bar">
          <Link className="back-pill-btn" to="/reporting">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="m12 19-7-7 7-7" />
              <path d="M19 12H5" />
            </svg>
            {t("reporting.back").text}
          </Link>
        </div>
        <p role="alert">{t("reporting.report.unknown").text}</p>
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

  const freshnessBadge = data.data ? (
    <p className="dashboard-freshness-pill">
      <svg className="dashboard-freshness-pill__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </svg>
      <span>
        {data.data.freshness
          ? t("reporting.freshness", undefined, { when: formatInstant(data.data.freshness) }).text
          : t("reporting.freshness.none").text}
      </span>
    </p>
  ) : null;

  return (
    <main className="shell-page report-viewer-page">
      <div className="report-back-bar">
        <Link className="back-pill-btn" to="/reporting">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("reporting.back").text}
        </Link>
      </div>

      <PageHeader
        icon={
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <path d="M14 2v6h6" />
            <path d="M8 13h8" />
            <path d="M8 17h5" />
          </svg>
        }
        title={t(definition.titleId).text}
        actions={freshnessBadge}
      />
      <p className="report-viewer-subtitle">{t(definition.decisionId).text}</p>

      <form
        className="modern-filter-panel report-viewer-controls"
        onSubmit={(event) => {
          event.preventDefault();
          setQuery(current);
        }}
      >
        {(definition.period || definition.location) && (
          <div className="report-viewer-controls__query">
            {definition.period && (
              <>
                <label className="report-viewer-field">
                  <span className="report-viewer-field__label">{t("reporting.field.from").text}</span>
                  <input
                    type="date"
                    value={form.from}
                    required
                    onChange={(event) => setForm({ ...form, from: event.target.value })}
                  />
                </label>
                <label className="report-viewer-field">
                  <span className="report-viewer-field__label">{t("reporting.field.to").text}</span>
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
              <label className="report-viewer-field report-viewer-field--location">
                <span className="report-viewer-field__label">{t("reporting.field.location").text}</span>
                <ReportingSelect
                  ariaLabel={t("reporting.field.location").text}
                  value={form.locationId}
                  onChange={(nextValue) => setForm({ ...form, locationId: nextValue })}
                  options={[
                    { value: "", label: t("reporting.field.location.all").text },
                    ...(locations.data ?? []).map((location) => ({
                      value: location.locationId,
                      label: locationText(location, intl.locale)
                    }))
                  ]}
                />
              </label>
            )}
          </div>
        )}
        <div className="report-viewer-controls__actions">
          <button type="submit" className="modern-btn modern-btn--primary report-viewer-btn">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
            <span>{t("reporting.show").text}</span>
          </button>
          {canExport && (
            <div className="report-viewer-controls__exports">
              <button type="button" className="report-viewer-btn report-viewer-btn--secondary" onClick={downloadCsv}>
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
                <span>{t("reporting.csv").text}</span>
              </button>
              <button
                type="button"
                className="report-viewer-btn report-viewer-btn--secondary"
                onClick={print}
                disabled={run?.status === "REQUESTED"}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <polyline points="6 9 6 2 18 2 18 9" />
                  <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
                  <rect x="6" y="14" width="12" height="8" />
                </svg>
                <span>{t("reporting.print").text}</span>
              </button>
            </div>
          )}
        </div>
      </form>

      {exportError && <p role="alert">{exportError}</p>}
      {run && (
        <p role="status" className="report-status-banner">
          {run.status === "REQUESTED" && t("reporting.print.waiting").text}
          {/* The link is the server's: anchored only when it is http(s) to our own origins (React 18 does not stop a javascript: href). */}
          {run.status === "READY" && run.downloadUrl && isOpenableFileUrl(run.downloadUrl) && (
            <a href={run.downloadUrl} target="_blank" rel="noopener noreferrer">
              {t("reporting.print.ready").text}
            </a>
          )}
          {run.status === "FAILED" && t("reporting.print.failed", undefined, { code: run.errorCode ?? "" }).text}
        </p>
      )}

      {data.isLoading && <p className="dashboard-status-note">{t("reporting.loading").text}</p>}
      {data.isError && <p role="alert">{errorText(data.error, t("reporting.error.generic").text)}</p>}
      {data.data && (
        <section className="dashboard-section">
          {data.data.rows.length === 0 ? (
            <p>{t("reporting.empty").text}</p>
          ) : (
            <div className="modern-table-card report-viewer-table-card">
              <div className="modern-table-scroll">
                <table className="modern-table reporting-data-table">
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
        </section>
      )}

      {canExport && runs.data && (
        <section className="dashboard-section">
          <div className="dashboard-section__header">
            <h2>{t("reporting.runs.title").text}</h2>
          </div>
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
                        <td>
                          <StateChip
                            state={earlier.status === "READY" ? "issued" : earlier.status === "FAILED" ? "alert" : "draft"}
                            label={t(`reporting.runs.status.${earlier.status}`).text}
                          />
                        </td>
                        <td>
                          {earlier.status === "READY" && (
                            <button
                              type="button"
                              className="report-viewer-btn report-viewer-btn--secondary"
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
