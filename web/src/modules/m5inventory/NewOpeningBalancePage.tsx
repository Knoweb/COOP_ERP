import { useState } from "react";
import type { ChangeEvent, DragEvent, FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale, skuText } from "../../shell/i18n/localName";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import {
  useInventoryApi,
  type BulkStockReport,
  type Sku,
  type StockRowPreview,
  type StockRowRequest
} from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { CustomSelect } from "./CustomSelect";
import { errorText, lineOf, rowReady, type CountedRow } from "./stockView";
import "./inventory.css";

/**
 * Prepare opening stock of an onboarding location (doc 25 section 4.7, 25A M5-10, PGL-M2-06):
 * Offers Excel spreadsheet import (.xlsx) with pre-check validation preview and inline correction,
 * alongside manual line entry. Committing creates an unconfirmed DRAFT document awaiting signatures.
 */
export function NewOpeningBalancePage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useInventoryApi();
  const navigate = useNavigate();
  const key = useIdempotencyKey();

  const [activeTab, setActiveTab] = useState<"excel" | "manual">("excel");
  const [locationId, setLocationId] = useState("");

  // Manual mode state
  const [rows, setRows] = useState<CountedRow[]>([]);

  // Excel mode state
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [sheetData, setSheetData] = useState<string>("");
  const [previewReport, setPreviewReport] = useState<BulkStockReport | null>(null);
  const [previewRows, setPreviewRows] = useState<StockRowPreview[]>([]);
  const [isDownloading, setIsDownloading] = useState(false);

  // Manual prepare mutation
  const prepareManual = useMutation({
    mutationFn: () => api.prepare(locationId, rows.map(lineOf), key.current()),
    onSuccess: (balance) => {
      key.next();
      navigate(`/inventory/opening/${balance.openingBalanceId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  // Excel validate mutation
  const validateExcel = useMutation({
    mutationFn: (overrideLines?: StockRowRequest[]) =>
      api.bulkImportOpeningStock(
        locationId,
        "VALIDATE",
        overrideLines ? undefined : sheetData,
        overrideLines,
        key.current()
      ),
    onSuccess: (report) => {
      setPreviewReport(report);
      setPreviewRows(report.previewRows ?? []);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  // Excel commit mutation
  const commitExcel = useMutation({
    mutationFn: (overrideLines?: StockRowRequest[]) =>
      api.bulkImportOpeningStock(
        locationId,
        "COMMIT",
        overrideLines ? undefined : sheetData,
        overrideLines,
        key.current()
      ),
    onSuccess: (report) => {
      key.next();
      if (report.openingBalanceId) {
        navigate(`/inventory/opening/${report.openingBalanceId}`);
      }
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  // Download template handler
  const handleDownloadTemplate = async () => {
    setIsDownloading(true);
    try {
      const blob = await api.downloadOpeningStockTemplate();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "opening_stock_template.xlsx";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => window.URL.revokeObjectURL(url), 100);
    } catch (err) {
      console.error("Failed to download opening stock template", err);
    } finally {
      setIsDownloading(false);
    }
  };

  // File loading helper
  const processFile = (file: File) => {
    setSelectedFile(file);
    setPreviewReport(null);
    setPreviewRows([]);

    const isBinary = file.name.endsWith(".xlsx") || file.type.includes("sheet") || file.type.includes("excel");
    const reader = new FileReader();

    if (isBinary) {
      reader.onload = (e) => {
        const buffer = e.target?.result as ArrayBuffer;
        if (buffer) {
          const bytes = new Uint8Array(buffer);
          let binary = "";
          for (let i = 0; i < bytes.byteLength; i++) {
            binary += String.fromCharCode(bytes[i]);
          }
          const base64 = btoa(binary);
          setSheetData(base64);
        }
      };
      reader.readAsArrayBuffer(file);
    } else {
      reader.onload = (e) => {
        const text = e.target?.result as string;
        setSheetData(text ?? "");
      };
      reader.readAsText(file);
    }
  };

  const handleFileInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      processFile(file);
    }
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    const file = event.dataTransfer.files?.[0];
    if (file) {
      processFile(file);
    }
  };

  const clearFile = () => {
    setSelectedFile(null);
    setSheetData("");
    setPreviewReport(null);
    setPreviewRows([]);
  };

  // Convert edited preview rows to request format
  const getConvertedPreviewLines = (): StockRowRequest[] => {
    return previewRows.map((r) => ({
      sku: r.skuCode ?? r.sku,
      uom: r.baseUom,
      qty: r.qty,
      unitCost: r.unitCost,
      batchNo: r.batchNo,
      expiryDate: r.expiryDate,
      printedMrp: r.printedMrp,
      condition: r.condition ?? "GOOD"
    }));
  };

  const updatePreviewRow = (index: number, change: Partial<StockRowPreview>) => {
    setPreviewRows((current) =>
      current.map((row, idx) => {
        if (idx !== index) return row;
        const updated = { ...row, ...change };
        if (updated.qty != null && updated.unitCost != null) {
          updated.extendedCost = Math.round(updated.qty * updated.unitCost * 100) / 100;
        }
        return updated;
      })
    );
  };

  // Manual row management
  const setRow = (index: number, change: Partial<CountedRow>) =>
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...change } : row)));

  const add = (sku: Sku) =>
    setRows((current) => [
      ...current,
      {
        skuId: sku.skuId,
        label: skuText(sku, locale),
        batchNo: "",
        expiryDate: "",
        printedMrp: "",
        qty: "",
        unitCost: "",
        condition: "GOOD"
      }
    ]);

  const cell = (
    index: number,
    field: "batchNo" | "expiryDate" | "printedMrp" | "qty" | "unitCost",
    type: string,
    labelId: string
  ) => (
    <td className={type === "number" ? "numeric-cell" : ""}>
      <input
        type={type}
        min={type === "number" ? "0" : undefined}
        step={type === "number" ? "any" : undefined}
        aria-label={t(labelId).text}
        value={rows[index][field]}
        onChange={(event) => setRow(index, { [field]: event.target.value })}
      />
    </td>
  );

  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/inventory">
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
          {t("inventory.back").text}
        </Link>
      </div>
      <PageHeader icon="stock" title={t("inventory.opening.new.title").text} />

      <div className="opening-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "excel"}
          className={`opening-tab-button ${activeTab === "excel" ? "opening-tab-button--active" : ""}`}
          onClick={() => setActiveTab("excel")}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
            <polyline points="10 9 9 9 8 9" />
          </svg>
          <span>{t("inventory.opening.tab.excel").text}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "manual"}
          className={`opening-tab-button ${activeTab === "manual" ? "opening-tab-button--active" : ""}`}
          onClick={() => setActiveTab("manual")}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
          </svg>
          <span>{t("inventory.opening.tab.manual").text}</span>
        </button>
      </div>

      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      {activeTab === "excel" ? (
        <section className="modern-table-card inventory-form-card opening-excel-card">
          <div className="inventory-card-header">
            <span className="inventory-card-header__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="16" y1="13" x2="8" y2="13" />
                <line x1="16" y1="17" x2="8" y2="17" />
                <polyline points="10 9 9 9 8 9" />
              </svg>
            </span>
            <h2 className="inventory-card-header__title">{t("inventory.opening.tab.excel").text}</h2>
          </div>
          <div className="opening-card-body">
            <div className="opening-template-bar">
              <button
                type="button"
                className="table-action-btn opening-template-btn"
                disabled={isDownloading}
                onClick={handleDownloadTemplate}
              >
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
                <span>{t("inventory.opening.template.download").text}</span>
              </button>
              {selectedFile && (
                <div className="opening-file-info">
                  <span className="opening-file-badge">
                    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                      <polyline points="14 2 14 8 20 8" />
                    </svg>
                    {t("inventory.opening.upload.file", undefined, {
                      name: selectedFile.name,
                      size: Math.round(selectedFile.size / 1024)
                    }).text}
                  </span>
                  <button type="button" className="btn-remove opening-file-clear" onClick={clearFile}>
                    {t("inventory.opening.upload.clear").text}
                  </button>
                </div>
              )}
            </div>

            <div
              className="opening-dropzone"
              onDragOver={(e) => e.preventDefault()}
              onDrop={handleDrop}
              onClick={() => document.getElementById("opening-stock-file-input")?.click()}
            >
              <input
                id="opening-stock-file-input"
                type="file"
                accept=".xlsx,.csv,.tsv"
                style={{ display: "none" }}
                onChange={handleFileInputChange}
              />
              <div className="opening-dropzone__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="17 8 12 3 7 8" />
                  <line x1="12" y1="3" x2="12" y2="15" />
                </svg>
              </div>
              <p className="opening-dropzone__text">{t("inventory.opening.upload.drop").text}</p>
            </div>

            <div className="inventory-action-row opening-action-row">
              <button
                type="button"
                className="table-action-btn opening-validate-btn"
                disabled={!locationId || !sheetData || validateExcel.isPending}
                onClick={() => validateExcel.mutate(undefined)}
              >
                {t("inventory.opening.btn.validate").text}
              </button>

              {previewReport && previewReport.status !== "EMPTY" && (
                <button
                  type="button"
                  className="inventory-submit-btn"
                  disabled={!locationId || (previewReport.errorRows != null && previewReport.errorRows > 0) || commitExcel.isPending}
                  onClick={() => commitExcel.mutate(getConvertedPreviewLines())}
                >
                  {t("inventory.opening.btn.commit").text}
                </button>
              )}
            </div>

            {validateExcel.isError && (
              <p role="alert" className="opening-error-msg">{errorText(validateExcel.error, t("inventory.error.generic").text)}</p>
            )}
            {commitExcel.isError && (
              <p role="alert" className="opening-error-msg">{errorText(commitExcel.error, t("inventory.error.generic").text)}</p>
            )}

            {previewReport && (
              <section className="opening-preview-section">
                <h2 className="opening-preview-title">{t("inventory.opening.preview.summary").text}</h2>
                <div className="opening-preview-kpi-grid">
                  <div className="opening-preview-kpi-card">
                    <span className="opening-preview-kpi-title">{t("inventory.opening.preview.total", undefined, { count: previewReport.totalRows }).text}</span>
                    <span className="opening-preview-kpi-value">{previewReport.totalRows}</span>
                  </div>
                  <div className="opening-preview-kpi-card">
                    <span className="opening-preview-kpi-title">{t("inventory.opening.preview.valid", undefined, { count: previewReport.validRows ?? 0 }).text}</span>
                    <span className="opening-preview-kpi-value">{previewReport.validRows ?? 0}</span>
                  </div>
                  <div className="opening-preview-kpi-card">
                    <span className="opening-preview-kpi-title">{t("inventory.opening.preview.errors", undefined, { count: previewReport.errorRows ?? 0 }).text}</span>
                    <span className="opening-preview-kpi-value">{previewReport.errorRows ?? 0}</span>
                  </div>
                  <div className="opening-preview-kpi-card">
                    <span className="opening-preview-kpi-title">
                      {t("inventory.opening.preview.valuation", undefined, { amount: "" }).text}
                    </span>
                    <span className="opening-preview-kpi-value">
                      <MoneyDisplay amount={previewReport.totalValuation ?? 0} />
                    </span>
                  </div>
                </div>

                {previewRows.length > 0 && (
                  <section className="modern-table-card" style={{ marginTop: 'var(--space-3)' }}>
                    <div className="modern-table-scroll">
                      <table className="modern-table stock-table opening-balance-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.opening.column.line_no").text}</th>
                          <th>{t("inventory.column.item").text}</th>
                          <th>{t("inventory.opening.column.uom").text}</th>
                          <th className="numeric-cell">{t("inventory.column.qty").text}</th>
                          <th className="numeric-cell">{t("inventory.column.unit_cost").text}</th>
                          <th className="numeric-cell">{t("inventory.opening.column.extended_cost").text}</th>
                          <th>{t("inventory.column.batch").text}</th>
                          <th>{t("inventory.column.expiry").text}</th>
                          <th className="numeric-cell">{t("inventory.column.mrp").text}</th>
                          <th>{t("inventory.column.condition").text}</th>
                          <th>{t("inventory.opening.column.status").text}</th>
                          <th>{t("inventory.opening.column.diagnostics").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {previewRows.map((row, idx) => (
                          <tr key={row.lineNo ?? idx}>
                            <td>{row.lineNo}</td>
                            <td>
                              <strong>{row.skuCode ?? row.sku}</strong>
                              {row.itemName && <div>{row.itemName}</div>}
                            </td>
                            <td>{row.baseUom ?? "-"}</td>
                            <td className="numeric-cell">
                              <input
                                type="number"
                                min="0"
                                step="any"
                                value={row.qty ?? ""}
                                onChange={(e) => updatePreviewRow(idx, { qty: Number(e.target.value) || undefined })}
                              />
                            </td>
                            <td className="numeric-cell">
                              <input
                                type="number"
                                min="0"
                                step="any"
                                value={row.unitCost ?? ""}
                                onChange={(e) => updatePreviewRow(idx, { unitCost: Number(e.target.value) || undefined })}
                              />
                            </td>
                            <td className="numeric-cell">
                              {row.extendedCost != null ? <MoneyDisplay amount={row.extendedCost} /> : "-"}
                            </td>
                            <td>
                              <input
                                type="text"
                                value={row.batchNo ?? ""}
                                onChange={(e) => updatePreviewRow(idx, { batchNo: e.target.value })}
                              />
                            </td>
                            <td>
                              <input
                                type="date"
                                value={row.expiryDate ?? ""}
                                onChange={(e) => updatePreviewRow(idx, { expiryDate: e.target.value })}
                              />
                            </td>
                            <td className="numeric-cell">
                              <input
                                type="number"
                                min="0"
                                step="any"
                                value={row.printedMrp ?? ""}
                                onChange={(e) => updatePreviewRow(idx, { printedMrp: Number(e.target.value) || undefined })}
                              />
                            </td>
                            <td>
                              <CustomSelect
                                portal
                                compact
                                ariaLabel={t("inventory.column.condition").text}
                                value={row.condition ?? "GOOD"}
                                onChange={(val) => updatePreviewRow(idx, { condition: val as "GOOD" | "DAMAGED" })}
                                options={[
                                  { value: "GOOD", label: t("inventory.condition.GOOD").text },
                                  { value: "DAMAGED", label: t("inventory.condition.DAMAGED").text }
                                ]}
                              />
                            </td>
                            <td>
                              <span
                                className={`opening-status-badge ${
                                  row.status === "VALID" ? "opening-status-badge--valid" : "opening-status-badge--error"
                                }`}
                              >
                                {t(`inventory.opening.preview.status.${row.status}`).text}
                              </span>
                            </td>
                            <td>{row.error ? t(row.error).text : "-"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}
            </section>
          )}
          </div>
        </section>
      ) : (
        <>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            prepareManual.mutate();
          }}
          className="inventory-form-row"
        >
          <section className="modern-table-card inventory-form-card" style={{ marginTop: 'var(--space-4)' }}>
            <div className="inventory-card-header">
              <span className="inventory-card-header__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                </svg>
              </span>
              <h2 className="inventory-card-header__title">{t("inventory.opening.tab.manual").text}</h2>
            </div>
            <div className="modern-table-scroll">
              <table className="modern-table stock-table opening-balance-table opening-balance-table--new">
                <colgroup>
                  <col className="col-item" />
                  <col className="col-batch" />
                  <col className="col-expiry" />
                  <col className="col-mrp" />
                  <col className="col-qty" />
                  <col className="col-unit-cost" />
                  <col className="col-condition" />
                  <col className="col-action" />
                </colgroup>
                <thead>
                  <tr>
                    <th>{t("inventory.column.item").text}</th>
                    <th>{t("inventory.column.batch").text}</th>
                    <th>{t("inventory.column.expiry").text}</th>
                    <th className="numeric-cell">{t("inventory.column.mrp").text}</th>
                    <th className="numeric-cell">{t("inventory.column.qty").text}</th>
                    <th className="numeric-cell">{t("inventory.column.unit_cost").text}</th>
                    <th>{t("inventory.column.condition").text}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.length === 0 ? (
                    <tr>
                      <td colSpan={8} className="empty-table-cell">
                        {t("inventory.opening.lines.empty").text}
                      </td>
                    </tr>
                  ) : (
                    rows.map((row, index) => (
                      <tr key={index}>
                        <td>{row.label}</td>
                        {cell(index, "batchNo", "text", "inventory.column.batch")}
                        {cell(index, "expiryDate", "date", "inventory.column.expiry")}
                        {cell(index, "printedMrp", "number", "inventory.column.mrp")}
                        {cell(index, "qty", "number", "inventory.column.qty")}
                        {cell(index, "unitCost", "number", "inventory.column.unit_cost")}
                        <td>
                          <CustomSelect
                            portal
                            compact
                            ariaLabel={t("inventory.column.condition").text}
                            value={row.condition}
                            onChange={(val) =>
                              setRow(index, { condition: val as CountedRow["condition"] })
                            }
                            options={[
                              { value: "GOOD", label: t("inventory.condition.GOOD").text },
                              { value: "DAMAGED", label: t("inventory.condition.DAMAGED").text }
                            ]}
                          />
                        </td>
                        <td>
                          <button
                            type="button"
                            className="btn-remove"
                            onClick={() => setRows((current) => current.filter((_, at) => at !== index))}
                          >
                            {t("inventory.remove").text}
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            <div className="inventory-action-bar" style={{ padding: 'var(--space-3) var(--space-4)' }}>
              <button
                type="submit"
                className="inventory-submit-btn"
                disabled={prepareManual.isPending || !locationId || rows.length === 0 || !rows.every(rowReady)}
              >
                {t("inventory.opening.prepare").text}
              </button>
            </div>
          </section>
          {prepareManual.isError && (
            <p role="alert" className="opening-error-msg">{errorText(prepareManual.error, t("inventory.error.generic").text)}</p>
          )}
        </form>
        <SkuFinder onPick={add} />
        </>
      )}
    </main>
  );
}

/** Finds catalogue items in use (LOCAL or SHARED) by code or name, and adds the one chosen as a line. */
function SkuFinder({ onPick }: { onPick: (sku: Sku) => void }) {
  const t = useT();
  const { locale } = useIntl();
  const api = useInventoryApi();
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Sku[] | null>(null);

  const search = async (event?: FormEvent) => {
    if (event) event.preventDefault();
    if (q.trim() === "") return;
    setFound(await api.searchSkus(q.trim()));
  };

  return (
    <section className="modern-table-card inventory-form-card" style={{ marginTop: 'var(--space-4)' }}>
      <div className="inventory-card-header">
        <span className="inventory-card-header__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
        </span>
        <h2 className="inventory-card-header__title">{t("inventory.field.find_item").text}</h2>
      </div>
      <div className="opening-card-body">
        <form onSubmit={(e) => void search(e)} className="repack-finder-field">
          <div className="repack-finder-row">
            <div className="repack-finder-input-wrapper">
              <svg
                className="repack-finder-search-icon"
                viewBox="0 0 24 24"
                width="16"
                height="16"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
                focusable="false"
              >
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                type="search"
                className="repack-finder-input"
                value={q}
                placeholder={t("inventory.field.find_item").text}
                onChange={(event) => setQ(event.target.value)}
              />
            </div>
            <button
              type="submit"
              className="repack-finder-btn"
              disabled={q.trim() === ""}
            >
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <span>{t("inventory.find").text}</span>
            </button>
          </div>

          {found?.length === 0 && <p className="repack-finder-empty">{t("inventory.find.none").text}</p>}
          {found && found.length > 0 && (
            <div className="repack-finder-dropdown-panel">
              <div className="repack-finder-dropdown-header">
                <span>{found.length} {t("inventory.column.item").text}</span>
                <button
                  type="button"
                  className="repack-finder-dismiss-btn"
                  onClick={() => setFound(null)}
                  aria-label={t("inventory.stock.explain.close").text}
                  title={t("inventory.stock.explain.close").text}
                >
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                </button>
              </div>
              <ul className="repack-finder-results-list" role="listbox">
                {found.map((sku) => (
                  <li key={sku.skuId} className="repack-finder-result-item" role="option" aria-selected="false">
                    <button
                      type="button"
                      className="repack-finder-result-btn"
                      onClick={() => {
                        onPick(sku);
                        setFound(null);
                      }}
                    >
                      <span className="repack-finder-result-code">{sku.skuCode}</span>
                      <span className="repack-finder-result-name">{inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </form>
      </div>
    </section>
  );
}
