import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useHasPermission } from "../../shell/auth/permissions";
import { useInventoryApi, type ReorderControl } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { errorText } from "./stockView";
import "./inventory.css";

export function ReorderControlsPage() {
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canManage = useHasPermission("inv.reorder.manage");

  const [locationId, setLocationId] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [skuId, setSkuId] = useState("");
  const [reorderLevel, setReorderLevel] = useState("");
  const [reorderQty, setReorderQty] = useState("");
  const [maxQty, setMaxQty] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [evalResult, setEvalResult] = useState<string | null>(null);

  const controlsQuery = useQuery({
    queryKey: ["inventory", "reorder-controls", locationId],
    queryFn: () => api.listReorderControls(locationId),
    enabled: locationId !== ""
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      setFormError(null);
      const level = parseFloat(reorderLevel);
      const qty = parseFloat(reorderQty);
      const max = maxQty.trim() !== "" ? parseFloat(maxQty) : undefined;

      if (isNaN(level) || level < 0) {
        throw new Error(t("inventory.reorder.level").text);
      }
      if (isNaN(qty) || qty <= 0) {
        throw new Error(t("inventory.reorder.qty").text);
      }
      if (max !== undefined && (isNaN(max) || max < level)) {
        throw new Error(t("inventory.reorder.max").text);
      }

      const idempotencyKey = crypto.randomUUID();
      return api.configureReorderControl(
        {
          locationId,
          skuId: skuId.trim(),
          reorderLevel: level,
          reorderQty: qty,
          maxQty: max
        },
        idempotencyKey
      );
    },
    onSuccess: () => {
      setShowForm(false);
      setSkuId("");
      setReorderLevel("");
      setReorderQty("");
      setMaxQty("");
      queryClient.invalidateQueries({ queryKey: ["inventory", "reorder-controls", locationId] });
    },
    onError: (err) => {
      setFormError(errorText(err, t("inventory.error.generic").text));
    }
  });

  const deleteMutation = useMutation({
    mutationFn: async (targetSkuId: string) => {
      const idempotencyKey = crypto.randomUUID();
      return api.deleteReorderControl(locationId, targetSkuId, idempotencyKey);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["inventory", "reorder-controls", locationId] });
    }
  });

  const evaluateMutation = useMutation({
    mutationFn: async () => {
      setEvalResult(null);
      const idempotencyKey = crypto.randomUUID();
      return api.evaluateLowStock(locationId, undefined, idempotencyKey);
    },
    onSuccess: (res) => {
      setEvalResult(
        t("inventory.reorder.evaluate_success", undefined, {
          evaluated: String(res.evaluatedCount),
          alerts: String(res.alertsTriggeredCount)
        }).text
      );
      queryClient.invalidateQueries({ queryKey: ["inventory", "reorder-controls", locationId] });
    }
  });

  const handleEdit = (ctrl: ReorderControl) => {
    setSkuId(ctrl.skuId);
    setReorderLevel(String(ctrl.reorderLevel));
    setReorderQty(String(ctrl.reorderQty));
    setMaxQty(ctrl.maxQty !== undefined && ctrl.maxQty !== null ? String(ctrl.maxQty) : "");
    setFormError(null);
    setShowForm(true);
  };

  const controls = controlsQuery.data ?? [];

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("inventory.reorder.title").text}
        actions={
          <div className="button-group">
            <Link className="action-link" to="/inventory">
              {t("inventory.back").text}
            </Link>
          </div>
        }
      />
      <p className="modern-intro-text">{t("inventory.reorder.intro").text}</p>

      <section className="modern-filter-panel modern-filter-panel--stock">
        <LocationPicker value={locationId} onChange={setLocationId} />
        {locationId !== "" && canManage && (
          <div className="inventory-action-bar">
            <button
              type="button"
              className="button button--primary"
              onClick={() => {
                setSkuId("");
                setReorderLevel("");
                setReorderQty("");
                setMaxQty("");
                setFormError(null);
                setShowForm(!showForm);
              }}
            >
              {t("inventory.reorder.add").text}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => evaluateMutation.mutate()}
              disabled={evaluateMutation.isPending}
            >
              {t("inventory.reorder.evaluate").text}
            </button>
          </div>
        )}
      </section>

      {evalResult && (
        <section className="notification-banner inventory-section">
          <p>{evalResult}</p>
        </section>
      )}

      {showForm && (
        <section className="inventory-reorder-card">
          <h3>{t("inventory.reorder.add").text}</h3>
          {formError && <p role="alert" className="error-text">{formError}</p>}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              saveMutation.mutate();
            }}
          >
            <label className="inventory-form-field">
              {t("inventory.reorder.item").text}
              <input
                id="reorder-skuId"
                type="text"
                className="input"
                required
                value={skuId}
                onChange={(e) => setSkuId(e.target.value)}
              />
            </label>

            <div className="inventory-reorder-grid">
              <label className="inventory-form-field">
                {t("inventory.reorder.level").text}
                <input
                  id="reorder-level"
                  type="number"
                  step="0.001"
                  min="0"
                  className="input"
                  required
                  value={reorderLevel}
                  onChange={(e) => setReorderLevel(e.target.value)}
                />
              </label>

              <label className="inventory-form-field">
                {t("inventory.reorder.qty").text}
                <input
                  id="reorder-qty"
                  type="number"
                  step="0.001"
                  min="0.001"
                  className="input"
                  required
                  value={reorderQty}
                  onChange={(e) => setReorderQty(e.target.value)}
                />
              </label>

              <label className="inventory-form-field">
                {t("inventory.reorder.max").text}
                <input
                  id="reorder-max"
                  type="number"
                  step="0.001"
                  min="0"
                  className="input"
                  value={maxQty}
                  onChange={(e) => setMaxQty(e.target.value)}
                />
              </label>
            </div>

            <div className="button-group">
              <button
                type="submit"
                className="button button--primary"
                disabled={saveMutation.isPending}
              >
                {t("inventory.reorder.save").text}
              </button>
              <button
                type="button"
                className="button button--ghost"
                onClick={() => setShowForm(false)}
              >
                {t("inventory.reorder.cancel").text}
              </button>
            </div>
          </form>
        </section>
      )}

      {controlsQuery.isLoading && <p>{t("inventory.loading").text}</p>}
      {controlsQuery.isError && (
        <p role="alert">{errorText(controlsQuery.error, t("inventory.error.generic").text)}</p>
      )}

      {locationId !== "" && !controlsQuery.isLoading && controls.length === 0 && (
        <p>{t("inventory.reorder.none").text}</p>
      )}

      {controls.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.column.item").text}</th>
                  <th>{t("inventory.column.on_hand").text}</th>
                  <th>{t("inventory.column.available").text}</th>
                  <th>{t("inventory.reorder.level").text}</th>
                  <th>{t("inventory.reorder.qty").text}</th>
                  <th>{t("inventory.reorder.max").text}</th>
                  <th>{t("inventory.repack.column.status").text}</th>
                  {canManage && <th>{t("inventory.pick.actions").text}</th>}
                </tr>
              </thead>
              <tbody>
                {controls.map((row) => (
                  <tr key={row.reorderControlId}>
                    <td><SkuLabel skuId={row.skuId} /></td>
                    <td className="numeric-cell">{row.currentOnHandQty}</td>
                    <td className="numeric-cell">{row.currentAvailableQty}</td>
                    <td className="numeric-cell">{row.reorderLevel}</td>
                    <td className="numeric-cell">{row.reorderQty}</td>
                    <td className="numeric-cell">
                      {row.maxQty !== undefined && row.maxQty !== null ? row.maxQty : "—"}
                    </td>
                    <td>
                      <StateChip
                        state={row.alertTriggered ? "void" : "issued"}
                        label={
                          row.alertTriggered
                            ? t("inventory.reorder.status_low").text
                            : t("inventory.reorder.status_ok").text
                        }
                      />
                    </td>
                    {canManage && (
                      <td>
                        <div className="button-group">
                          <button
                            type="button"
                            className="button button--small button--ghost"
                            onClick={() => handleEdit(row)}
                          >
                            {t("inventory.reorder.edit").text}
                          </button>
                          <button
                            type="button"
                            className="button button--small button--ghost"
                            onClick={() => deleteMutation.mutate(row.skuId)}
                            disabled={deleteMutation.isPending}
                          >
                            {t("inventory.reorder.delete").text}
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}
