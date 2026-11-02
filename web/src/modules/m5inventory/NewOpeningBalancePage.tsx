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
import {
  useInventoryApi,
  type BulkStockReport,
  type Sku,
  type StockRowPreview,
  type StockRowRequest
} from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
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
      window.URL.revokeObjectURL(url);
    } catch {
      // download failed
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
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        {t("inventory.back").text}
      </Link>
      <h1>{t("inventory.opening.new.title").text}</h1>

      <div className="opening-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "excel"}
          className={`opening-tab-button ${activeTab === "excel" ? "opening-tab-button--active" : ""}`}
          onClick={() => setActiveTab("excel")}
        >
          {t("inventory.opening.tab.excel").text}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "manual"}
          className={`opening-tab-button ${activeTab === "manual" ? "opening-tab-button--active" : ""}`}
          onClick={() => setActiveTab("manual")}
        >
          {t("inventory.opening.tab.manual").text}
        </button>
      </div>

      <LocationPicker value={locationId} onChange={setLocationId} />

      {activeTab === "excel" ? (
        <section className="inventory-excel-import-section">
          <div className="opening-template-bar">
            <button
              type="button"
              className="btn-secondary"
              disabled={isDownloading}
              onClick={handleDownloadTemplate}
            >
              {t("inventory.opening.template.download").text}
            </button>
            {selectedFile && (
              <div className="opening-file-info">
                <span>
                  {t("inventory.opening.upload.file", undefined, {
                    name: selectedFile.name,
                    size: Math.round(selectedFile.size / 1024)
                  }).text}
                </span>
                <button type="button" className="btn-remove" onClick={clearFile}>
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
            <p>{t("inventory.opening.upload.drop").text}</p>
          </div>

          <div className="inventory-action-row">
            <button
              type="button"
              disabled={!locationId || !sheetData || validateExcel.isPending}
              onClick={() => validateExcel.mutate(undefined)}
            >
              {t("inventory.opening.btn.validate").text}
            </button>

            {previewReport && previewReport.status !== "EMPTY" && (
              <button
                type="button"
                className="btn-primary"
                disabled={!locationId || (previewReport.errorRows != null && previewReport.errorRows > 0) || commitExcel.isPending}
                onClick={() => commitExcel.mutate(getConvertedPreviewLines())}
              >
                {t("inventory.opening.btn.commit").text}
              </button>
            )}
          </div>

          {validateExcel.isError && (
            <p role="alert">{errorText(validateExcel.error, t("inventory.error.generic").text)}</p>
          )}
          {commitExcel.isError && (
            <p role="alert">{errorText(commitExcel.error, t("inventory.error.generic").text)}</p>
          )}

          {previewReport && (
            <section className="opening-preview-section">
              <h2>{t("inventory.opening.preview.summary").text}</h2>
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
                <section className="modern-table-card">
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
                              <select
                                value={row.condition ?? "GOOD"}
                                onChange={(e) => updatePreviewRow(idx, { condition: e.target.value as "GOOD" | "DAMAGED" })}
                              >
                                <option value="GOOD">{t("inventory.condition.GOOD").text}</option>
                                <option value="DAMAGED">{t("inventory.condition.DAMAGED").text}</option>
                              </select>
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
          <section className="modern-table-card">
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
                          <select
                            aria-label={t("inventory.column.condition").text}
                            value={row.condition}
                            onChange={(event) =>
                              setRow(index, { condition: event.target.value as CountedRow["condition"] })
                            }
                          >
                            <option value="GOOD">{t("inventory.condition.GOOD").text}</option>
                            <option value="DAMAGED">{t("inventory.condition.DAMAGED").text}</option>
                          </select>
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
          </section>
          <div>
            <button
              type="submit"
              disabled={prepareManual.isPending || !locationId || rows.length === 0 || !rows.every(rowReady)}
            >
              {t("inventory.opening.prepare").text}
            </button>
          </div>
          {prepareManual.isError && (
            <p role="alert">{errorText(prepareManual.error, t("inventory.error.generic").text)}</p>
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

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setFound(await api.searchSkus(q.trim()));
  };

  return (
    <form onSubmit={search} className="inventory-search-form">
      <label className="inventory-form-field">
        {t("inventory.field.find_item").text}
        <input type="search" value={q} onChange={(event) => setQ(event.target.value)} />
      </label>
      <div>
        <button type="submit" disabled={!q.trim()}>
          {t("inventory.find").text}
        </button>
      </div>
      {found?.length === 0 && <p>{t("inventory.find.none").text}</p>}
      {found && found.length > 0 && (
        <ul className="inventory-list">
          {found.map((sku) => (
            <li key={sku.skuId}>
              <button type="button" onClick={() => onPick(sku)}>
                {
                  t("inventory.add_item", undefined, {
                    code: sku.skuCode,
                    name: inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa)
                  }).text
                }
              </button>
            </li>
          ))}
        </ul>
      )}
    </form>
  );
}
