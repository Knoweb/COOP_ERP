import React, { useEffect, useId, useMemo, useState } from "react";
import { useT } from "../../shell/i18n/useT";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useInventoryApi, type LotBalance, type Sku } from "./inventoryApi";
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

  return (
    <div className="inventory-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="inventory-modal">
        <h2 id={titleId} className="inventory-modal-title">
          {t("inventory.reprice.modal_title").text}
        </h2>

        <div className="inventory-modal-summary">
          <div>
            <strong>{sku?.skuCode ?? lot.skuId}</strong>
            {sku?.nameEn && <span> — {sku.nameEn}</span>}
          </div>
          <div className="inventory-modal-summary-grid">
            <div>
              <span>{t("inventory.column.batch").text}: </span>
              <strong>{lot.batchNo || "—"}</strong>
            </div>
            <div>
              <span>{t("inventory.reprice.available_qty").text}: </span>
              <strong>{lot.qtyOnHand}</strong>
            </div>
            <div>
              <span>{t("inventory.reprice.current_cost").text}: </span>
              <strong>
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

        <form onSubmit={handleSubmit}>
          <div className="inventory-modal-grid-2">
            <div>
              <label htmlFor="reprice-qty" className="inventory-form-field">
                {t("inventory.reprice.qty_label").text} *
                <input
                  id="reprice-qty"
                  type="number"
                  step="0.001"
                  min="0.001"
                  max={lot.qtyOnHand ?? undefined}
                  required
                  value={qty}
                  onChange={(e) => setQty(e.target.value)}
                  className="input"
                  disabled={submitting}
                />
              </label>
            </div>

            <div>
              <label htmlFor="reprice-cost" className="inventory-form-field">
                {t("inventory.reprice.new_cost_label").text} *
                <input
                  id="reprice-cost"
                  type="number"
                  step="0.0001"
                  min="0"
                  required
                  value={newUnitCost}
                  onChange={(e) => setNewUnitCost(e.target.value)}
                  className="input"
                  disabled={submitting}
                />
              </label>
            </div>
          </div>

          <div className="inventory-modal-field-group">
            <label htmlFor="reprice-target-condition" className="inventory-form-field">
              {t("inventory.reprice.target_condition").text} *
              <select
                id="reprice-target-condition"
                value={targetCondition}
                onChange={(e) => setTargetCondition(e.target.value as "GOOD" | "DAMAGED")}
                className="input"
                disabled={submitting}
              >
                <option value="GOOD">{t("inventory.condition.GOOD").text}</option>
                <option value="DAMAGED">{t("inventory.condition.DAMAGED").text}</option>
              </select>
            </label>
          </div>

          {valueAdjustment !== null && (
            <div className="inventory-modal-diff-row">
              <span>{t("inventory.reprice.value_diff").text}:</span>
              <strong>
                <MoneyDisplay amount={parseFloat(valueAdjustment)} />
              </strong>
            </div>
          )}

          {blendedCost !== null && (
            <div className="inventory-modal-blended-row">
              <span>{t("inventory.reprice.blended_cost_note").text}:</span>
              <strong>
                <MoneyDisplay amount={parseFloat(blendedCost)} />
              </strong>
            </div>
          )}

          <div className="inventory-modal-field-group">
            <label htmlFor="reprice-reason" className="inventory-form-field">
              {t("inventory.reprice.reason_label").text} *
              <textarea
                id="reprice-reason"
                required
                maxLength={500}
                rows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder={t("inventory.reprice.reason_placeholder").text}
                className="input"
                disabled={submitting}
              />
            </label>
          </div>

          <div className="inventory-modal-actions">
            <button
              type="button"
              className="button button--secondary"
              onClick={onClose}
              disabled={submitting}
            >
              {t("inventory.cancel").text}
            </button>
            <button
              type="submit"
              className="button button--primary"
              disabled={submitting}
            >
              {submitting ? t("inventory.reprice.submitting").text : t("inventory.reprice.submit").text}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
