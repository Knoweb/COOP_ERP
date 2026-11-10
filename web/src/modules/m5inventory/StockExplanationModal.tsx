import { useEffect, useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useInventoryApi } from "./inventoryApi";
import { errorText, isSyntheticBatchNo } from "./stockView";

export interface StockExplanationModalProps {
  isOpen: boolean;
  onClose: () => void;
  locationId: string;
  skuId: string | null;
  skuCode?: string;
  skuName?: string;
}

type TabType = "lots" | "reservations" | "transfers" | "count";

export function StockExplanationModal({
  isOpen,
  onClose,
  locationId,
  skuId,
  skuCode,
  skuName
}: StockExplanationModalProps) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useInventoryApi();
  const titleId = useId();
  const [activeTab, setActiveTab] = useState<TabType>("lots");

  useEffect(() => {
    if (isOpen) {
      setActiveTab("lots");
    }
  }, [isOpen, skuId]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && isOpen) {
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  const explanationQuery = useQuery({
    queryKey: ["inventory", "stock-explanation", locationId, skuId],
    queryFn: () => (skuId ? api.stockExplanation(locationId, skuId) : null),
    enabled: isOpen && Boolean(skuId) && Boolean(locationId)
  });

  if (!isOpen || !skuId) {
    return null;
  }

  const data = explanationQuery.data;
  const lots = data?.lots ?? [];
  const reservations = data?.reservations ?? [];
  const incomingTransfers = data?.incomingTransfers ?? [];
  const outgoingTransfers = data?.outgoingTransfers ?? [];
  const allTransfers = [
    ...incomingTransfers.map((tx) => ({ ...tx, direction: "incoming" as const })),
    ...outgoingTransfers.map((tx) => ({ ...tx, direction: "outgoing" as const }))
  ];
  const count = data?.latestCount;

  const displayName = skuName || data?.skuNameEn || skuCode || data?.skuCode || skuId;
  const displayCode = skuCode || data?.skuCode;

  return (
    <div
      className="inventory-modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="inventory-modal stock-explanation-modal">
        <div className="stock-modal-header">
          <div>
            <h2 id={titleId} className="inventory-modal-title">
              {t("inventory.stock.explain.title").text}
            </h2>
            <div className="stock-modal-sku-subtitle">
              {displayCode && <span className="stock-sku-badge">{displayCode}</span>}
              <span className="stock-sku-name">{displayName}</span>
              {data?.baseUomCode && (
                <span className="stock-pill-badge">{data.baseUomCode}</span>
              )}
              {data?.departmentNameEn && (
                <span className="stock-pill-badge stock-pill-badge--dept">
                  {data.departmentNameEn}
                </span>
              )}
            </div>
          </div>
          <button
            type="button"
            className="stock-modal-close-btn"
            onClick={onClose}
            aria-label={t("inventory.stock.explain.close").text}
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
              focusable="false"
            >
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        {explanationQuery.isLoading && (
          <div className="stock-modal-loading">
            <p>{t("inventory.loading").text}</p>
          </div>
        )}

        {explanationQuery.isError && (
          <div role="alert" className="inventory-modal-alert">
            {errorText(explanationQuery.error, t("inventory.error.generic").text)}
          </div>
        )}

        {data && (
          <>
            <div className="stock-modal-kpi-summary">
              <div className="stock-modal-kpi-item stock-modal-kpi-item--on-hand">
                <div className="stock-modal-kpi-content">
                  <span className="stock-modal-kpi-label">
                    {t("inventory.stock.column.on_hand").text}
                  </span>
                  <span className="stock-modal-kpi-val">{data.qtyOnHand}</span>
                </div>
                <div className="stock-modal-kpi-icon-badge" aria-hidden="true">
                  <svg
                    viewBox="0 0 24 24"
                    width="16"
                    height="16"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    focusable="false"
                  >
                    <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
                    <path d="m3.3 7 8.7 5 8.7-5" />
                    <path d="M12 22V12" />
                  </svg>
                </div>
              </div>

              <div className="stock-modal-kpi-item stock-modal-kpi-item--reserved">
                <div className="stock-modal-kpi-content">
                  <span className="stock-modal-kpi-label">
                    {t("inventory.stock.column.reserved").text}
                  </span>
                  <span className="stock-modal-kpi-val stock-modal-kpi-val--warn">
                    {data.reservedQty}
                  </span>
                </div>
                <div className="stock-modal-kpi-icon-badge" aria-hidden="true">
                  <svg
                    viewBox="0 0 24 24"
                    width="16"
                    height="16"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    focusable="false"
                  >
                    <rect x="4" y="11" width="16" height="10" rx="2" />
                    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
                  </svg>
                </div>
              </div>

              <div className="stock-modal-kpi-item stock-modal-kpi-item--available">
                <div className="stock-modal-kpi-content">
                  <span className="stock-modal-kpi-label">
                    {t("inventory.stock.column.available").text}
                  </span>
                  <span className="stock-modal-kpi-val stock-modal-kpi-val--strong">
                    {data.availableQty}
                  </span>
                </div>
                <div className="stock-modal-kpi-icon-badge" aria-hidden="true">
                  <svg
                    viewBox="0 0 24 24"
                    width="16"
                    height="16"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    focusable="false"
                  >
                    <circle cx="12" cy="12" r="9" />
                    <polyline points="8.5 12 11 14.5 15.5 9.5" />
                  </svg>
                </div>
              </div>

              <div className="stock-modal-kpi-item stock-modal-kpi-item--projected">
                <div className="stock-modal-kpi-content">
                  <span className="stock-modal-kpi-label">
                    {t("inventory.stock.explain.remaining").text}
                  </span>
                  <span className="stock-modal-kpi-val">{data.remainingStockQty}</span>
                </div>
                <div className="stock-modal-kpi-icon-badge" aria-hidden="true">
                  <svg
                    viewBox="0 0 24 24"
                    width="16"
                    height="16"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    focusable="false"
                  >
                    <path d="m22 7-8.5 8.5-5-5L2 17" />
                    <polyline points="16 7 22 7 22 13" />
                  </svg>
                </div>
              </div>
            </div>

            <nav className="stock-modal-tabs" aria-label={t("inventory.stock.explain.title").text}>
              <button
                type="button"
                className={`stock-modal-tab-btn ${activeTab === "lots" ? "stock-modal-tab-btn--active" : ""}`}
                onClick={() => setActiveTab("lots")}
              >
                {t("inventory.stock.explain.tab.lots", undefined, { count: lots.length }).text}
              </button>
              <button
                type="button"
                className={`stock-modal-tab-btn ${activeTab === "reservations" ? "stock-modal-tab-btn--active" : ""}`}
                onClick={() => setActiveTab("reservations")}
              >
                {t("inventory.stock.explain.tab.reservations", undefined, { count: reservations.length }).text}
              </button>
              <button
                type="button"
                className={`stock-modal-tab-btn ${activeTab === "transfers" ? "stock-modal-tab-btn--active" : ""}`}
                onClick={() => setActiveTab("transfers")}
              >
                {t("inventory.stock.explain.tab.transfers", undefined, { count: allTransfers.length }).text}
              </button>
              <button
                type="button"
                className={`stock-modal-tab-btn ${activeTab === "count" ? "stock-modal-tab-btn--active" : ""}`}
                onClick={() => setActiveTab("count")}
              >
                {t("inventory.stock.explain.tab.count").text}
              </button>
            </nav>

            <div className="stock-modal-tab-body">
              {activeTab === "lots" && (
                <div className="stock-modal-table-wrap">
                  {lots.length === 0 ? (
                    <p className="stock-modal-empty-text">
                      {t("inventory.stock.explain.no_lots").text}
                    </p>
                  ) : (
                    <table className="modern-table stock-modal-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.column.batch").text}</th>
                          <th>{t("inventory.column.expiry").text}</th>
                          <th>{t("inventory.column.condition").text}</th>
                          <th>{t("inventory.column.fefo").text}</th>
                          <th className="numeric-cell">{t("inventory.column.on_hand").text}</th>
                          <th className="numeric-cell">{t("inventory.column.unit_cost").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {lots.map((lot) => (
                          <tr key={lot.stockLotId}>
                            <td>
                              {isSyntheticBatchNo(lot.batchNo)
                                ? t("inventory.field.batch_not_tracked").text
                                : (lot.batchNo || "—")}
                            </td>
                            <td>{lot.expiryDate ? formatDate(lot.expiryDate) : "—"}</td>
                            <td>
                              <span className="condition-chip">
                                {t(`inventory.condition.${lot.condition}`).text}
                              </span>
                            </td>
                            <td>
                              {lot.expired ? (
                                <span className="condition-chip">{t("inventory.lot.expired").text}</span>
                              ) : (
                                (lot.fefoRank ?? "—")
                              )}
                            </td>
                            <td className="numeric-cell">{lot.qtyOnHand}</td>
                            <td className="numeric-cell">
                              {lot.unitCost != null ? <MoneyDisplay amount={lot.unitCost} /> : "—"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )}

              {activeTab === "reservations" && (
                <div className="stock-modal-table-wrap">
                  {reservations.length === 0 ? (
                    <p className="stock-modal-empty-text">
                      {t("inventory.stock.explain.no_reservations").text}
                    </p>
                  ) : (
                    <table className="modern-table stock-modal-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.stock.explain.doc_no").text}</th>
                          <th>{t("inventory.stock.explain.status").text}</th>
                          <th className="numeric-cell">{t("inventory.stock.column.reserved").text}</th>
                          <th>{t("inventory.stock.explain.date").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {reservations.map((res) => (
                          <tr key={res.pickListId}>
                            <td>{res.deliveryDocNumber || res.pickListId.slice(0, 8)}</td>
                            <td>
                              <span className="condition-chip">{res.pickStatus}</span>
                            </td>
                            <td className="numeric-cell">{res.reservedQty}</td>
                            <td>{res.reservedAt ? formatDate(res.reservedAt) : "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )}

              {activeTab === "transfers" && (
                <div className="stock-modal-table-wrap">
                  {allTransfers.length === 0 ? (
                    <p className="stock-modal-empty-text">
                      {t("inventory.stock.explain.no_transfers").text}
                    </p>
                  ) : (
                    <table className="modern-table stock-modal-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.stock.explain.direction").text}</th>
                          <th>{t("inventory.stock.explain.doc_no").text}</th>
                          <th>{t("inventory.stock.explain.from_to").text}</th>
                          <th className="numeric-cell">{t("inventory.stock.explain.qty").text}</th>
                          <th>{t("inventory.stock.explain.status").text}</th>
                          <th>{t("inventory.stock.explain.date").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {allTransfers.map((tx, idx) => (
                          <tr key={`${tx.transferId}-${idx}`}>
                            <td>
                              <span
                                className={`stock-direction-badge stock-direction-badge--${tx.direction}`}
                              >
                                {tx.direction === "incoming"
                                  ? t("inventory.stock.in_transit.incoming", undefined, { qty: tx.qty }).text
                                  : t("inventory.stock.in_transit.outgoing", undefined, { qty: tx.qty }).text}
                              </span>
                            </td>
                            <td>{tx.documentNo || tx.transferId.slice(0, 8)}</td>
                            <td>{tx.locationName || tx.locationId?.slice(0, 8) || "—"}</td>
                            <td className="numeric-cell">{tx.qty}</td>
                            <td>
                              <span className="condition-chip">{tx.status}</span>
                            </td>
                            <td>{tx.dispatchedAt ? formatDate(tx.dispatchedAt) : "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )}

              {activeTab === "count" && (
                <div className="stock-modal-count-wrap">
                  {!count ? (
                    <p className="stock-modal-empty-text">
                      {t("inventory.stock.explain.no_count").text}
                    </p>
                  ) : (
                    <div className="stock-count-audit-card">
                      <div className="stock-count-audit-grid">
                        <div>
                          <span className="stock-count-label">
                            {t("inventory.stock.explain.doc_no").text}:
                          </span>
                          <strong>{count.taskId.slice(0, 8)}</strong>
                        </div>
                        <div>
                          <span className="stock-count-label">
                            {t("inventory.stock.explain.status").text}:
                          </span>
                          <span className="condition-chip">{count.status}</span>
                        </div>
                        {count.outcome && (
                          <div>
                            <span className="stock-count-label">
                              {t("inventory.stock.explain.outcome").text}:
                            </span>
                            <span
                              className={`variance-badge variance-badge--${count.outcome === "MATCHED" ? "matched" : "discrepant"}`}
                            >
                              {t(
                                count.outcome === "MATCHED"
                                  ? "inventory.stock.status.matched"
                                  : "inventory.stock.status.discrepant"
                              ).text}
                            </span>
                          </div>
                        )}
                        <div>
                          <span className="stock-count-label">
                            {t("inventory.stock.explain.expected_qty").text}:
                          </span>
                          <strong>{count.expectedQty}</strong>
                        </div>
                        <div>
                          <span className="stock-count-label">
                            {t("inventory.stock.explain.counted_qty").text}:
                          </span>
                          <strong>{count.countedQty}</strong>
                        </div>
                        <div>
                          <span className="stock-count-label">
                            {t("inventory.stock.explain.variance").text}:
                          </span>
                          <strong
                            className={
                              count.varianceQty !== 0
                                ? "stock-modal-kpi-val--warn"
                                : "stock-modal-kpi-val--ok"
                            }
                          >
                            {count.varianceQty > 0 ? `+${count.varianceQty}` : count.varianceQty}
                          </strong>
                        </div>
                        {count.varianceValue != null && (
                          <div>
                            <span className="stock-count-label">
                              {t("inventory.stock.explain.variance_val").text}:
                            </span>
                            <strong>
                              <MoneyDisplay amount={count.varianceValue} />
                            </strong>
                          </div>
                        )}
                        {count.submittedAt && (
                          <div>
                            <span className="stock-count-label">
                              {t("inventory.stock.explain.date").text}:
                            </span>
                            <span>{formatDate(count.submittedAt)}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </>
        )}

        <div className="inventory-modal-actions">
          <button type="button" className="button button--secondary" onClick={onClose}>
            {t("inventory.stock.explain.close").text}
          </button>
        </div>
      </div>
    </div>
  );
}
