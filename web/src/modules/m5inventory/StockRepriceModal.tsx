import React, { useEffect, useId, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { useT } from "../../shell/i18n/useT";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useInventoryApi, type LotBalance, type Sku } from "./inventoryApi";
import { CustomSelect } from "./CustomSelect";
import { errorText } from "./stockView";

export interface StockRepriceModalProps {
  isOpen: boolean;
  onClose: () => void;
  lot: LotBalance | null;
  sku: Sku | null;
  onSuccess: () => void;
}

export function StockRepriceModal({
  isOpen,
  onClose,
  lot,
  sku,
  onSuccess
}: StockRepriceModalProps) {
  const t = useT();
  const api = useInventoryApi();
  const repriceKey = useIdempotencyKey();

  const titleId = useId();
  const [qty, setQty] = useState("");
  const [newUnitCost, setNewUnitCost] = useState("");
  const [targetCondition, setTargetCondition] = useState<"GOOD" | "DAMAGED">("GOOD");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (lot && isOpen) {
      setQty(lot.qtyOnHand != null ? lot.qtyOnHand.toString() : "");
      setNewUnitCost(lot.unitCost != null ? lot.unitCost.toString() : "");
      setTargetCondition(lot.condition === "DAMAGED" ? "DAMAGED" : "GOOD");
      setReason("");
      setError(null);
    }
  }, [lot, isOpen]);

  const parsedQty = parseFloat(qty);
  const parsedCost = parseFloat(newUnitCost);
  const currentCost = lot?.unitCost ?? 0;
  const currentQty = lot?.qtyOnHand ?? 0;

  const valueAdjustment = useMemo(() => {
    if (isNaN(parsedQty) || isNaN(parsedCost) || parsedQty <= 0) {
      return null;
    }
    return (parsedQty * (parsedCost - currentCost)).toFixed(2);
  }, [parsedQty, parsedCost, currentCost]);

  const blendedCost = useMemo(() => {
    if (
      isNaN(parsedQty) ||
      isNaN(parsedCost) ||
      parsedQty <= 0 ||
      parsedQty >= currentQty ||
      targetCondition !== lot?.condition
    ) {
      return null;
    }
    const remaining = currentQty - parsedQty;
    return ((parsedQty * parsedCost + remaining * currentCost) / currentQty).toFixed(4);
  }, [parsedQty, parsedCost, currentQty, currentCost, targetCondition, lot?.condition]);

  if (!isOpen || !lot) {
    return null;
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);

    if (isNaN(parsedQty) || parsedQty <= 0) {
      setError(t("inventory.reprice.invalid_qty").text);
      return;
    }

    if (parsedQty > currentQty) {
      setError(t("inventory.reprice.qty_exceeded").text);
      return;
    }

    if (isNaN(parsedCost) || parsedCost < 0) {
      setError(t("inventory.reprice.invalid_cost").text);
      return;
    }

    if (!reason.trim()) {
      setError(t("inventory.reprice.reason_required").text);
      return;
    }

    setSubmitting(true);
    try {
      await api.repriceStockLot(
        lot.stockLotId,
        {
          qty: parsedQty,
          newUnitCost: parsedCost,
          targetCondition,
          reason: reason.trim()
        },
        repriceKey.current()
      );
      repriceKey.next();
      onSuccess();
      onClose();
    } catch (err) {
      setError(errorText(err, t("inventory.error.generic").text));
    } finally {
      setSubmitting(false);
    }
  };

  const modalNode = (
    <div
      className="inventory-modal-backdrop stock-reprice-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClick={(e) => {
        if (e.target === e.currentTarget && !submitting) {
          onClose();
        }
      }}
    >
      <div className="inventory-modal stock-reprice-modal">
        {/* Header with icon badge, title & close button */}
        <div className="stock-modal-header stock-reprice-modal__header">
          <div className="stock-reprice-modal__header-title-wrap">
            <div className="stock-reprice-modal__icon-badge" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
                <line x1="7" y1="7" x2="7.01" y2="7" />
              </svg>
            </div>
            <h2 id={titleId} className="inventory-modal-title stock-reprice-modal__title">
              {t("inventory.reprice.modal_title").text}
            </h2>
          </div>
          <button
            type="button"
            className="stock-modal-close-btn"
            onClick={onClose}
            disabled={submitting}
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

        {/* Info summary card */}
        <div className="inventory-modal-summary stock-reprice-summary-card">
          <div className="stock-reprice-summary-card__top">
            <span className="stock-reprice-sku-badge">{sku?.skuCode ?? lot.skuId}</span>
            {sku?.nameEn && <span className="stock-reprice-sku-name">{sku.nameEn}</span>}
          </div>
          <div className="inventory-modal-summary-grid stock-reprice-summary-grid">
            <div className="stock-reprice-summary-item">
              <span className="stock-reprice-summary-label">{t("inventory.column.batch").text}</span>
              <strong className="stock-reprice-summary-value">{lot.batchNo || "—"}</strong>
            </div>
            <div className="stock-reprice-summary-item">
              <span className="stock-reprice-summary-label">{t("inventory.reprice.available_qty").text}</span>
              <strong className="stock-reprice-summary-value">{lot.qtyOnHand}</strong>
            </div>
            <div className="stock-reprice-summary-item">
              <span className="stock-reprice-summary-label">{t("inventory.reprice.current_cost").text}</span>
              <strong className="stock-reprice-summary-value stock-reprice-summary-value--money">
                <MoneyDisplay amount={lot.unitCost ?? 0} />
              </strong>
            </div>
          </div>
        </div>

        {error && (
          <div role="alert" className="inventory-modal-alert">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="stock-reprice-form">
          <div className="inventory-modal-grid-2">
            <div>
              <label htmlFor="reprice-qty" className="inventory-form-field stock-reprice-field-label">
                <span className="stock-reprice-label-text">
                  {t("inventory.reprice.qty_label").text}
                  <span className="register-field-required"> *</span>
                </span>
                <input
                  id="reprice-qty"
                  type="number"
                  step="0.001"
                  min="0.001"
                  max={lot.qtyOnHand ?? undefined}
                  required
                  value={qty}
                  onChange={(e) => setQty(e.target.value)}
                  className="input modern-input stock-reprice-input"
                  disabled={submitting}
                />
              </label>
            </div>

            <div>
              <label htmlFor="reprice-cost" className="inventory-form-field stock-reprice-field-label">
                <span className="stock-reprice-label-text">
                  {t("inventory.reprice.new_cost_label").text}
                  <span className="register-field-required"> *</span>
                </span>
                <input
                  id="reprice-cost"
                  type="number"
                  step="0.0001"
                  min="0"
                  required
                  value={newUnitCost}
                  onChange={(e) => setNewUnitCost(e.target.value)}
                  className="input modern-input stock-reprice-input"
                  disabled={submitting}
                />
              </label>
            </div>
          </div>

          <div className="inventory-modal-field-group">
            <label htmlFor="reprice-target-condition" className="inventory-form-field stock-reprice-field-label">
              <span className="stock-reprice-label-text">
                {t("inventory.reprice.target_condition").text}
                <span className="register-field-required"> *</span>
              </span>
              <CustomSelect
                id="reprice-target-condition"
                portal
                value={targetCondition}
                onChange={(val) => setTargetCondition(val as "GOOD" | "DAMAGED")}
                ariaLabel={t("inventory.reprice.target_condition").text}
                disabled={submitting}
                options={[
                  { value: "GOOD", label: t("inventory.condition.GOOD").text },
                  { value: "DAMAGED", label: t("inventory.condition.DAMAGED").text }
                ]}
              />
            </label>
          </div>

          {valueAdjustment !== null && (
            <div
              className={`inventory-modal-diff-row stock-reprice-diff-card ${
                parseFloat(valueAdjustment) < 0
                  ? "stock-reprice-diff-card--down"
                  : parseFloat(valueAdjustment) > 0
                    ? "stock-reprice-diff-card--up"
                    : ""
              }`}
            >
              <div className="stock-reprice-diff-card__left">
                <svg
                  viewBox="0 0 24 24"
                  width="18"
                  height="18"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span className="stock-reprice-diff-card__label">{t("inventory.reprice.value_diff").text}:</span>
              </div>
              <strong className="stock-reprice-diff-card__amount">
                <MoneyDisplay amount={parseFloat(valueAdjustment)} />
              </strong>
            </div>
          )}

          {blendedCost !== null && (
            <div className="inventory-modal-blended-row stock-reprice-blended-card">
              <div className="stock-reprice-blended-card__left">
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
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="16" x2="12" y2="12" />
                  <line x1="12" y1="8" x2="12.01" y2="8" />
                </svg>
                <span>{t("inventory.reprice.blended_cost_note").text}:</span>
              </div>
              <strong>
                <MoneyDisplay amount={parseFloat(blendedCost)} />
              </strong>
            </div>
          )}

          <div className="inventory-modal-field-group">
            <label htmlFor="reprice-reason" className="inventory-form-field stock-reprice-field-label">
              <span className="stock-reprice-label-text">
                {t("inventory.reprice.reason_label").text}
                <span className="register-field-required"> *</span>
              </span>
              <textarea
                id="reprice-reason"
                required
                maxLength={500}
                rows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder={t("inventory.reprice.reason_placeholder").text}
                className="input modern-input stock-reprice-textarea"
                disabled={submitting}
              />
            </label>
          </div>

          <div className="inventory-modal-actions stock-reprice-modal__actions">
            <button
              type="button"
              className="button button--secondary modern-btn modern-btn--secondary"
              onClick={onClose}
              disabled={submitting}
            >
              {t("inventory.cancel").text}
            </button>
            <button
              type="submit"
              className="button button--primary modern-btn modern-btn--primary"
              disabled={submitting}
            >
              {submitting ? t("inventory.reprice.submitting").text : t("inventory.reprice.submit").text}
            </button>
          </div>
        </form>
      </div>
    </div>
  );

  return typeof document !== "undefined" ? createPortal(modalNode, document.body) : modalNode;
}
