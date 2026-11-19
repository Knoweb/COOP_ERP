import { useRef, useState } from "react";
import type { ChangeEvent, DragEvent, FormEvent } from "react";
import { useT } from "../../shell/i18n/useT";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { usePricingApi, type PriceListImportReport } from "./pricingApi";
import { errorText, reasonMessageId } from "./priceListState";
import "./pricing.css";

export interface PriceListImportModalProps {
  isOpen: boolean;
  onClose: () => void;
  listId: string;
  listName: string;
  kind: string;
  onImportSuccess: () => void;
}

/**
 * Bulk price list importer modal for draft price lists (CAT-13).
 *
 * Supports CSV/TSV spreadsheet upload to populate draft trade or shelf price lists,
 * shows parsing diagnostics and validation outcomes, and updates the draft without
 * activating or publishing prices.
 */
export function PriceListImportModal({
  isOpen,
  onClose,
  listId,
  listName,
  kind,
  onImportSuccess
}: PriceListImportModalProps) {
  const t = useT();
  const api = usePricingApi();
  const importKey = useIdempotencyKey();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [fileName, setFileName] = useState("");
  const [csvContent, setCsvContent] = useState("");
  const [estimatedRows, setEstimatedRows] = useState(0);
  const [replace, setReplace] = useState(true);
  const [isDragOver, setIsDragOver] = useState(false);
  const [report, setReport] = useState<PriceListImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!isOpen) return null;

  const handleFile = (file: File) => {
    if (!file) return;
    setReport(null);
    setError(null);
    const reader = new FileReader();
    reader.onload = (e) => {
      const text = (e.target?.result as string) || "";
      setFileName(file.name);
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

  const downloadSampleTemplate = () => {
    const isTrade = kind === "TRADE";
    const header = isTrade ? "code,uom,tier,price\n" : "code,price\n";
    const rows = isTrade
      ? "SKU-001,EA,0,100.00\nSKU-001,EA,10,95.00\nSKU-002,EA,0,250.00\n"
      : "SKU-001,100.00\nSKU-002,250.00\n";
    const blob = new Blob([header + rows], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `price_list_template_${kind.toLowerCase()}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!csvContent) return;
    setSubmitting(true);
    setError(null);

    try {
      const res = await api.importPriceListLines(
        listId,
        { sheet: csvContent, replace },
        importKey.current()
      );
      importKey.next();
      setReport(res);
      if (res.saved) {
        onImportSuccess();
      }
    } catch (err) {
      importKey.next();
      setError(errorText(err, t("pricing.error.generic").text));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="pricing-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="pricing-import-title">
      <div className="pricing-modal-card">
        <div className="pricing-modal-header">
          <h2 id="pricing-import-title" className="pricing-modal-title">
            {t("pricing.import.modal_title").text}
          </h2>
          <button
            type="button"
            className="pricing-modal-close"
            onClick={onClose}
            aria-label={t("pricing.import.close").text}
          >
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        <p className="pricing-modal-description">
          {t("pricing.import.description").text} — <strong>{listName}</strong>
        </p>

        <form onSubmit={handleSubmit} className="pricing-import-form">
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.tsv,.txt"
            onChange={onFileInputChange}
            style={{ display: "none" }}
            aria-label={t("pricing.import.file_select").text}
          />

          <div
            className={`pricing-dropzone ${isDragOver ? "pricing-dropzone--active" : ""}`}
            onDragOver={(e) => {
              e.preventDefault();
              setIsDragOver(true);
            }}
            onDragLeave={() => setIsDragOver(false)}
            onDrop={onDrop}
            onClick={() => fileInputRef.current?.click()}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                fileInputRef.current?.click();
              }
            }}
          >
            <svg
              className="pricing-dropzone-icon"
              viewBox="0 0 24 24"
              width="36"
              height="36"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            <p className="pricing-dropzone-prompt">
              {fileName
                ? t("pricing.import.file_selected", undefined, { fileName, rowCount: estimatedRows }).text
                : t("pricing.import.file_select").text}
            </p>
          </div>

          <div className="pricing-import-options">
            <label className="pricing-checkbox-label">
              <input
                type="checkbox"
                checked={replace}
                onChange={(e) => setReplace(e.target.checked)}
              />
              <span>{t("pricing.import.replace_label").text}</span>
            </label>

            <button
              type="button"
              className="pricing-link-btn"
              onClick={downloadSampleTemplate}
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
              {t("pricing.import.template_download").text}
            </button>
          </div>

          {error && (
            <div role="alert" className="pricing-alert-box pricing-alert-box--error">
              {error}
            </div>
          )}

          {report && report.saved && (
            <div role="status" className="pricing-alert-box pricing-alert-box--success">
              {t("pricing.import.success", undefined, { count: report.importedLines }).text}
            </div>
          )}

          {report && !report.saved && (
            <div role="alert" className="pricing-alert-box pricing-alert-box--error">
              {t("pricing.import.failure", undefined, { count: report.problems.length || report.errorRows }).text}
            </div>
          )}

          {report && report.problems.length > 0 && (
            <div className="pricing-import-problems-table-container">
              <table className="modern-table pricing-import-problems-table">
                <thead>
                  <tr>
                    <th>{t("pricing.import.column_line").text}</th>
                    <th>{t("pricing.import.column_item").text}</th>
                    <th>{t("pricing.import.column_problem").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {report.problems.map((p, idx) => (
                    <tr key={idx}>
                      <td>#{p.line}</td>
                      <td>{p.sku || "—"}</td>
                      <td>
                        <span className="pricing-alert-pill">
                          {t(reasonMessageId(p.message)).text}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="pricing-modal-actions">
            <button
              type="button"
              className="modern-btn modern-btn--secondary"
              onClick={onClose}
            >
              {t("pricing.import.close").text}
            </button>
            <button
              type="submit"
              className="modern-btn modern-btn--primary"
              disabled={submitting || !csvContent}
            >
              {t("pricing.import.submit").text}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
