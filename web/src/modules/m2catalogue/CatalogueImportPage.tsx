import { useState, useRef } from "react";
import type { FormEvent, ChangeEvent, DragEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { PageHeader } from "../../shell/components/PageHeader";
import { useCatalogueApi, type ImportReportResponse } from "./catalogueApi";
import { errorText } from "./skuView";


/**
 * Bulk catalogue import page for stewards (CAT-01, 22A section 6.2).
 *
 * Supports drag-and-drop or file selection of ~20,000 SKU spreadsheets (.csv),
 * client-side inspection, pre-commit validation with duplicate suspect scoring,
 * and high-performance bulk insertion.
 */
export function CatalogueImportPage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [fileName, setFileName] = useState<string>("");
  const [csvContent, setCsvContent] = useState<string>("");
  const [estimatedRows, setEstimatedRows] = useState<number>(0);
  const [report, setReport] = useState<ImportReportResponse | null>(null);
  const [isDragOver, setIsDragOver] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<"suspects" | "errors">("suspects");

  const importMutation = useMutation({
    mutationFn: (mode: "VALIDATE" | "COMMIT") =>
      api.importSkus({ mode, csvContent }, idempotencyKey.current()),
    onSuccess: (data, variables) => {
      idempotencyKey.next();
      setReport(data);
      if (variables === "COMMIT") {
        queryClient.invalidateQueries({ queryKey: ["catalogue", "skus"] });
      }
      if (data.errors && data.errors.length > 0) {
        setActiveTab("errors");
      } else {
        setActiveTab("suspects");
      }
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });

  const handleFile = (file: File) => {
    if (!file) return;
    setFileName(file.name);
    setReport(null);
    const reader = new FileReader();
    reader.onload = (e) => {
      const text = (e.target?.result as string) || "";
      setCsvContent(text);
      // Rough estimation of row count minus header
      const lines = text.split(/\r\n|\r|\n/).filter((l) => l.trim().length > 0);
      setEstimatedRows(Math.max(0, lines.length - 1));
    };
    reader.readAsText(file);
  };

  const onFileInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) handleFile(file);
  };

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) handleFile(file);
  };

  const onDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragOver(true);
  };

  const onDragLeave = () => {
    setIsDragOver(false);
  };

  const handleValidate = (e: FormEvent) => {
    e.preventDefault();
    if (!csvContent) return;
    importMutation.mutate("VALIDATE");
  };

  const handleCommit = (e: FormEvent) => {
    e.preventDefault();
    if (!csvContent) return;
    importMutation.mutate("COMMIT");
  };

  const handleReset = () => {
    setFileName("");
    setCsvContent("");
    setEstimatedRows(0);
    setReport(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const getScoreBadgeClass = (score: number) => {
    if (score >= 0.95) return "score-badge--critical";
    if (score >= 0.85) return "score-badge--high";
    return "score-badge--moderate";
  };

  return (
    <main className="shell-page catalogue-import-page">
      <Link className="back-link" to="/catalogue">
        <svg viewBox="0 0 24 24" width="16" height="16">
          <path d="M15 18l-6-6 6-6" fill="none" stroke="currentColor" strokeWidth="2" />
        </svg>
        {t("catalogue.back").text}
      </Link>

      <PageHeader
        icon="catalogue"
        title={t("catalogue.import.title").text}
      />
      <p className="catalogue-import-subtitle">{t("catalogue.import.description").text}</p>

      {/* Upload Zone */}
      {!csvContent ? (
        <div
          className={`catalogue-dropzone ${isDragOver ? "catalogue-dropzone--active" : ""}`}
          onDrop={onDrop}
          onDragOver={onDragOver}
          onDragLeave={onDragLeave}
          onClick={() => fileInputRef.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") fileInputRef.current?.click();
          }}
          aria-label={t("catalogue.import.upload_prompt").text}
        >
          <svg className="catalogue-dropzone__icon" viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242" />
            <path d="M12 12v9" />
            <path d="m8 16 4-4 4 4" />
          </svg>
          <p className="catalogue-dropzone__prompt">{t("catalogue.import.upload_prompt").text}</p>
          <span className="catalogue-dropzone__hint">{t("catalogue.import.file_hint").text}</span>
          <input
            type="file"
            ref={fileInputRef}
            onChange={onFileInputChange}
            accept=".csv,text/csv,text/plain"
            style={{ display: "none" }}
          />
        </div>
      ) : (
        <div className="catalogue-file-card">
          <div className="catalogue-file-card__info">
            <div className="catalogue-file-card__icon">
              <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="16" y1="13" x2="8" y2="13" />
                <line x1="16" y1="17" x2="8" y2="17" />
                <polyline points="10 9 9 9 8 9" />
              </svg>
            </div>
            <div>
              <h2 className="catalogue-file-card__name">{fileName}</h2>
              <span className="catalogue-file-card__meta">
                ~{estimatedRows.toLocaleString()} {t("catalogue.import.summary.total").text.toLowerCase()}
              </span>
            </div>
          </div>

          <div className="catalogue-file-card__actions">
            <button
              type="button"
              className="action-button action-button--secondary"
              onClick={handleReset}
              disabled={importMutation.isPending}
            >
              {t("catalogue.import.reupload").text}
            </button>
            <button
              type="button"
              className="action-button"
              onClick={handleValidate}
              disabled={importMutation.isPending}
            >
              {importMutation.isPending && importMutation.variables === "VALIDATE"
                ? t("catalogue.import.validating", undefined, { count: estimatedRows }).text
                : t("catalogue.import.validate_button").text}
            </button>
            <button
              type="button"
              className="action-button action-button--primary"
              onClick={handleCommit}
              disabled={importMutation.isPending || (report !== null && report.status === "REJECTED")}
            >
              {importMutation.isPending && importMutation.variables === "COMMIT"
                ? t("catalogue.import.importing", undefined, { count: estimatedRows }).text
                : t("catalogue.import.commit_button").text}
            </button>
          </div>
        </div>
      )}

      {/* Mutation Error */}
      {importMutation.isError && (
        <div className="import-alert import-alert--error" role="alert">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
          </svg>
          <span>{errorText(importMutation.error, t("catalogue.error.generic").text)}</span>
        </div>
      )}

      {/* Report Dashboard */}
      {report && (
        <section className="catalogue-import-results" aria-live="polite">
          {/* Status Banner */}
          <div
            className={`import-status-banner import-status-banner--${report.status.toLowerCase()}`}
          >
            <div className="import-status-banner__title">
              {report.status === "VALIDATED" && t("catalogue.import.status.validated").text}
              {report.status === "ACCEPTED" &&
                t("catalogue.import.status.accepted", undefined, {
                  accepted: report.accepted,
                  total: report.rows
                }).text}
              {report.status === "REJECTED" &&
                t("catalogue.import.status.rejected", undefined, {
                  errors: report.rejected
                }).text}
            </div>
          </div>

          {/* KPI Stat Cards */}
          <div className="import-stats-grid">
            <div className="import-stat-card">
              <span className="import-stat-card__label">{t("catalogue.import.summary.total").text}</span>
              <span className="import-stat-card__value">{report.rows.toLocaleString()}</span>
            </div>
            <div className="import-stat-card import-stat-card--success">
              <span className="import-stat-card__label">{t("catalogue.import.summary.accepted").text}</span>
              <span className="import-stat-card__value">{report.accepted.toLocaleString()}</span>
            </div>
            <div className={`import-stat-card ${report.rejected > 0 ? "import-stat-card--error" : ""}`}>
              <span className="import-stat-card__label">{t("catalogue.import.summary.errors").text}</span>
              <span className="import-stat-card__value">{report.rejected.toLocaleString()}</span>
            </div>
            <div className={`import-stat-card ${report.suspects.length > 0 ? "import-stat-card--warning" : ""}`}>
              <span className="import-stat-card__label">{t("catalogue.import.summary.suspects").text}</span>
              <span className="import-stat-card__value">{report.suspects.length.toLocaleString()}</span>
            </div>
          </div>

          {/* Navigation Tabs */}
          <div className="import-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === "suspects"}
              className={`import-tab ${activeTab === "suspects" ? "import-tab--active" : ""}`}
              onClick={() => setActiveTab("suspects")}
            >
              {t("catalogue.import.suspects_title").text} ({report.suspects.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === "errors"}
              className={`import-tab ${activeTab === "errors" ? "import-tab--active" : ""}`}
              onClick={() => setActiveTab("errors")}
            >
              {t("catalogue.import.errors_title").text} ({report.errors.length})
            </button>
          </div>

          {/* Tab Content: Duplicate Suspects */}
          {activeTab === "suspects" && (
            <div className="import-tab-panel" role="tabpanel">
              {report.suspects.length === 0 ? (
                <p className="import-empty-state">{t("catalogue.import.no_suspects").text}</p>
              ) : (
                <div className="table-container">
                  <table className="import-table">
                    <thead>
                      <tr>
                        <th>{t("catalogue.import.row").text}</th>
                        <th>{t("catalogue.import.candidate").text}</th>
                        <th>{t("catalogue.import.matched_item").text}</th>
                        <th>{t("catalogue.import.score").text}</th>
                        <th>{t("catalogue.import.reasons").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.suspects.map((suspect, index) => (
                        <tr key={`${suspect.rowNumber}-${index}`}>
                          <td className="cell-row-num">#{suspect.rowNumber}</td>
                          <td className="cell-candidate">{suspect.candidateName}</td>
                          <td className="cell-matched">
                            {suspect.matchedSkuId ? (
                              <Link
                                to={`/catalogue/skus/${suspect.matchedSkuId}`}
                                target="_blank"
                                rel="noreferrer"
                                className="item-link"
                              >
                                <span className="item-code">{suspect.matchedSkuCode}</span>
                                <span className="item-name">{suspect.matchedSkuName}</span>
                              </Link>
                            ) : (
                              <span>
                                <span className="item-code">{suspect.matchedSkuCode}</span>
                                <span className="item-name">{suspect.matchedSkuName}</span>
                              </span>
                            )}
                          </td>
                          <td>
                            <span className={`score-badge ${getScoreBadgeClass(suspect.score)}`}>
                              {(suspect.score * 100).toFixed(0)}%
                            </span>
                          </td>
                          <td>
                            <div className="reasons-list">
                              {suspect.reasons.map((r, rIdx) => (
                                <span key={rIdx} className="reason-tag">
                                  {r}
                                </span>
                              ))}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* Tab Content: Validation Errors */}
          {activeTab === "errors" && (
            <div className="import-tab-panel" role="tabpanel">
              {report.errors.length === 0 ? (
                <p className="import-empty-state">{t("catalogue.import.no_errors").text}</p>
              ) : (
                <div className="table-container">
                  <table className="import-table">
                    <thead>
                      <tr>
                        <th>{t("catalogue.import.row").text}</th>
                        <th>{t("catalogue.import.field").text}</th>
                        <th>{t("catalogue.column.code").text}</th>
                        <th>{t("catalogue.import.col_message").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.errors.map((err, index) => (
                        <tr key={`${err.rowNumber}-${index}`} className="row--error">
                          <td className="cell-row-num">#{err.rowNumber}</td>
                          <td className="cell-field"><code>{err.field}</code></td>
                          <td className="cell-code"><code>{err.code}</code></td>
                          <td className="cell-message">{err.message}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </section>
      )}
    </main>
  );
}
