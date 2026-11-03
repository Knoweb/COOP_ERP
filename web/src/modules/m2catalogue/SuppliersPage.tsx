import { useState, useRef } from "react";
import type { FormEvent, ChangeEvent, DragEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import {
  useCatalogueApi,
  type SupplierImportReportResponse
} from "./catalogueApi";
import { errorText } from "./skuView";

/**
 * Suppliers management page (22A section 8, Suppliers).
 * Displays suppliers of the current entity and allows registering new suppliers
 * or performing bulk CSV migrations with dry-run validation and conflict detection.
 */
export function SuppliersPage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canManage = useHasPermission("cat.supplier.manage");
  const registerKey = useIdempotencyKey();
  const importKey = useIdempotencyKey();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [activeTab, setActiveTab] = useState<"directory" | "import">("directory");
  const [name, setName] = useState("");
  const [successMsg, setSuccessMsg] = useState("");

  // Bulk import state
  const [fileName, setFileName] = useState<string>("");
  const [csvContent, setCsvContent] = useState<string>("");
  const [estimatedRows, setEstimatedRows] = useState<number>(0);
  const [report, setReport] = useState<SupplierImportReportResponse | null>(null);
  const [isDragOver, setIsDragOver] = useState<boolean>(false);

  const suppliersQuery = useQuery({
    queryKey: ["catalogue", "suppliers"],
    queryFn: () => api.listSuppliers()
  });

  const registerMutation = useMutation({
    mutationFn: () =>
      api.registerSupplier(
        { name: name.trim() },
        registerKey.current()
      ),
    onSuccess: () => {
      registerKey.next();
      setName("");
      setSuccessMsg(t("catalogue.suppliers.success").text);
      queryClient.invalidateQueries({ queryKey: ["catalogue", "suppliers"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        registerKey.next();
      }
    }
  });

  const importMutation = useMutation({
    mutationFn: (mode: "VALIDATE" | "COMMIT") =>
      api.importSuppliers({ mode, csvContent }, importKey.current()),
    onSuccess: (data, mode) => {
      importKey.next();
      setReport(data);
      if (mode === "COMMIT" && data.status === "ACCEPTED") {
        queryClient.invalidateQueries({ queryKey: ["catalogue", "suppliers"] });
      }
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        importKey.next();
      }
    }
  });

  const handleRegisterSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSuccessMsg("");
    if (!name.trim()) return;
    registerMutation.mutate();
  };

  const handleFile = (file: File) => {
    if (!file) return;
    setFileName(file.name);
    setReport(null);
    const reader = new FileReader();
    reader.onload = (e) => {
      const text = (e.target?.result as string) || "";
      setCsvContent(text);
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

  const handleResetImport = () => {
    setFileName("");
    setCsvContent("");
    setEstimatedRows(0);
    setReport(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const suppliers = suppliersQuery.data ?? [];

  return (
    <main className="shell-page">
      <div style={{ marginBottom: "var(--space-2)" }}>
        <Link className="back-pill-btn" to="/catalogue">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("catalogue.back").text}
        </Link>
      </div>

      <PageHeader
        icon="catalogue"
        title={t("catalogue.suppliers.title").text}
      />
      <p className="catalogue-import-subtitle">{t("catalogue.suppliers.description").text}</p>

      {/* Tabs */}
      <div className="import-tabs" role="tablist" style={{ marginBottom: "var(--space-4)" }}>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "directory"}
          className={`import-tab ${activeTab === "directory" ? "import-tab--active" : ""}`}
          onClick={() => setActiveTab("directory")}
        >
          {t("catalogue.suppliers.tab_list").text}
        </button>
        {canManage && (
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "import"}
            className={`import-tab ${activeTab === "import" ? "import-tab--active" : ""}`}
            onClick={() => setActiveTab("import")}
          >
            {t("catalogue.suppliers.tab_import").text}
          </button>
        )}
      </div>

      {activeTab === "directory" && (
        <>
          {/* Register Supplier Form */}
          {canManage && (
            <section className="catalogue-section supplier-register-card">
              <h2>{t("catalogue.suppliers.register").text}</h2>
              <form onSubmit={handleRegisterSubmit} className="supplier-register-form">
                <div className="supplier-register-field-wrap">
                  <label className="catalogue-form-field">
                    <span className="catalogue-field-label">{t("catalogue.suppliers.name").text}</span>
                    <input
                      type="text"
                      required
                      maxLength={120}
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      placeholder={t("catalogue.suppliers.placeholder").text}
                      className="supplier-name-input"
                    />
                  </label>
                </div>
                <button
                  type="submit"
                  className="register-submit-btn supplier-register-btn"
                  disabled={registerMutation.isPending || !name.trim()}
                >
                  {t("catalogue.suppliers.submit").text}
                </button>
              </form>

              {successMsg && (
                <div className="import-alert import-alert--success" role="status" style={{ marginTop: "var(--space-2)" }}>
                  <span>{successMsg}</span>
                </div>
              )}

              {registerMutation.isError && (
                <p role="alert" className="error-text" style={{ marginTop: "var(--space-2)" }}>
                  {errorText(registerMutation.error, t("catalogue.error.generic").text)}
                </p>
              )}
            </section>
          )}

          {/* Suppliers Table */}
          <section className="catalogue-section">
            {suppliersQuery.isLoading && <p>{t("catalogue.loading").text}</p>}

            {suppliers.length === 0 && !suppliersQuery.isLoading && (
              <div className="supplier-empty-state">
                <p>{t("catalogue.suppliers.empty").text}</p>
              </div>
            )}

            {suppliers.length > 0 && (
              <div className="modern-table-card">
                <div className="modern-table-scroll">
                  <table className="modern-table catalogue-table">
                    <thead>
                      <tr>
                        <th>{t("catalogue.suppliers.name").text}</th>
                        <th>{t("catalogue.suppliers.code").text}</th>
                        <th>{t("catalogue.column.status").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {suppliers.map((s) => {
                        const chipState: ChipState = s.status === "ACTIVE" ? "issued" : "void";
                        return (
                          <tr key={s.supplierId}>
                            <td>{s.name}</td>
                            <td>{s.supplierCode ?? "—"}</td>
                            <td>
                              <StateChip
                                state={chipState}
                                label={t(`catalogue.supplier.status.${s.status}`).text}
                              />
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </section>
        </>
      )}

      {activeTab === "import" && canManage && (
        <section className="catalogue-section supplier-import-section">
          <h2>{t("catalogue.suppliers.import.title").text}</h2>
          <p className="catalogue-import-subtitle" style={{ marginBottom: "var(--space-3)" }}>
            {t("catalogue.suppliers.import.description").text}
          </p>

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
              aria-label={t("catalogue.suppliers.import.dropzone").text}
            >
              <svg className="catalogue-dropzone__icon" viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242" />
                <path d="M12 12v9" />
                <path d="m8 16 4-4 4 4" />
              </svg>
              <p className="catalogue-dropzone__prompt">{t("catalogue.suppliers.import.dropzone").text}</p>
              <span className="catalogue-dropzone__hint">{t("catalogue.suppliers.import.file_hint").text}</span>
              <input
                type="file"
                ref={fileInputRef}
                onChange={onFileInputChange}
                accept=".csv,text/csv,text/plain"
                style={{ display: "none" }}
              />
            </div>
          ) : (
            <div className="catalogue-file-card" style={{ marginBottom: "var(--space-4)" }}>
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
                    ~{estimatedRows.toLocaleString()} {t("catalogue.suppliers.import.summary.total").text.toLowerCase()}
                  </span>
                </div>
              </div>

              <div className="catalogue-file-card__actions">
                <button
                  type="button"
                  className="action-button action-button--secondary"
                  onClick={handleResetImport}
                  disabled={importMutation.isPending}
                >
                  {t("catalogue.suppliers.import.reupload").text}
                </button>
                <button
                  type="button"
                  className="action-button"
                  onClick={handleValidate}
                  disabled={importMutation.isPending}
                >
                  {importMutation.isPending && importMutation.variables === "VALIDATE"
                    ? t("catalogue.suppliers.import.validating").text
                    : t("catalogue.suppliers.import.validate_button").text}
                </button>
                <button
                  type="button"
                  className="action-button action-button--primary"
                  onClick={handleCommit}
                  disabled={importMutation.isPending || (report !== null && report.status === "REJECTED")}
                >
                  {importMutation.isPending && importMutation.variables === "COMMIT"
                    ? t("catalogue.suppliers.import.importing").text
                    : t("catalogue.suppliers.import.commit_button").text}
                </button>
              </div>
            </div>
          )}

          {importMutation.isError && (
            <div className="import-alert import-alert--error" role="alert" style={{ marginBottom: "var(--space-3)" }}>
              <span>{errorText(importMutation.error, t("catalogue.error.generic").text)}</span>
            </div>
          )}

          {report && (
            <div className="catalogue-import-results" aria-live="polite" style={{ marginTop: "var(--space-4)" }}>
              {/* Status Banner */}
              <div className={`import-status-banner import-status-banner--${report.status.toLowerCase()}`}>
                <div className="import-status-banner__title">
                  {report.status === "VALIDATED" && t("catalogue.suppliers.import.status.validated").text}
                  {report.status === "ACCEPTED" &&
                    t("catalogue.suppliers.import.status.accepted", undefined, {
                      created: report.created,
                      skipped: report.skipped
                    }).text}
                  {report.status === "REJECTED" &&
                    t("catalogue.suppliers.import.status.rejected", undefined, {
                      errors: report.rejected
                    }).text}
                </div>
              </div>

              {/* KPI Stat Cards */}
              <div className="import-stats-grid" style={{ marginBottom: "var(--space-4)" }}>
                <div className="import-stat-card">
                  <span className="import-stat-card__label">{t("catalogue.suppliers.import.summary.total").text}</span>
                  <span className="import-stat-card__value">{report.rows.toLocaleString()}</span>
                </div>
                <div className="import-stat-card import-stat-card--success">
                  <span className="import-stat-card__label">{t("catalogue.suppliers.import.summary.created").text}</span>
                  <span className="import-stat-card__value">{report.created.toLocaleString()}</span>
                </div>
                <div className="import-stat-card">
                  <span className="import-stat-card__label">{t("catalogue.suppliers.import.summary.skipped").text}</span>
                  <span className="import-stat-card__value">{report.skipped.toLocaleString()}</span>
                </div>
                <div className={`import-stat-card ${report.rejected > 0 ? "import-stat-card--error" : ""}`}>
                  <span className="import-stat-card__label">{t("catalogue.suppliers.import.summary.errors").text}</span>
                  <span className="import-stat-card__value">{report.rejected.toLocaleString()}</span>
                </div>
              </div>

              {/* Row-level Errors Table */}
              {report.errors && report.errors.length > 0 && (
                <div className="modern-table-card">
                  <div className="modern-table-scroll">
                    <table className="modern-table import-table">
                      <thead>
                        <tr>
                          <th>{t("catalogue.suppliers.import.error_row").text}</th>
                          <th>{t("catalogue.suppliers.import.error_field").text}</th>
                          <th>{t("catalogue.suppliers.import.error_code").text}</th>
                          <th>{t("catalogue.suppliers.import.error_message").text}</th>
                          <th>{t("catalogue.suppliers.import.error_value").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {report.errors.map((err, idx) => (
                          <tr key={`${err.rowNumber}-${idx}`}>
                            <td className="cell-row-num">{err.rowNumber}</td>
                            <td>{err.field}</td>
                            <td><code>{err.code}</code></td>
                            <td>{err.message}</td>
                            <td>{err.rejectedValue ? <code>{err.rejectedValue}</code> : "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          )}
        </section>
      )}
    </main>
  );
}
