import { useState } from "react";
import { Link } from "react-router-dom";
import { useIntl } from "react-intl";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { inLocale } from "../../shell/i18n/localName";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi, type Sku } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { CustomSelect } from "./CustomSelect";
import { SkuLabel } from "./SkuLabel";
import { expectedOutputOf, quantityOf } from "./stockControl";
import { errorText, isSyntheticBatchNo } from "./stockView";
import { PageHeader } from "../../shell/components/PageHeader";
import "./inventory.css";

/** Finds an item of the catalogue by code or name and hands the chosen one back. */
function SkuFinder({
  label,
  buttonLabel,
  chosenId,
  onChoose
}: {
  label: string;
  buttonLabel: string;
  chosenId: string;
  onChoose: (sku: Sku) => void;
}) {
  const t = useT();
  const api = useInventoryApi();
  const { locale } = useIntl();
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Sku[] | null>(null);

  const search = async () => {
    if (q.trim() === "") return;
    setFound(await api.searchSkus(q.trim()));
  };

  return (
    <div className="repack-finder-field">
      <span className="repack-field-label">{label}</span>
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
            placeholder={label}
            onChange={(event) => setQ(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void search();
              }
            }}
          />
        </div>
        <button
          type="button"
          className="repack-finder-btn"
          disabled={q.trim() === ""}
          onClick={() => void search()}
        >
          <svg
            viewBox="0 0 24 24"
            width="15"
            height="15"
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
          <span>{buttonLabel}</span>
        </button>
      </div>

      {chosenId !== "" && (
        <div className="repack-chosen-indicator">
          <span className="repack-chosen-label">{t("inventory.column.item").text}:</span>
          <span className="repack-chosen-name">
            <SkuLabel skuId={chosenId} />
          </span>
        </div>
      )}

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
          <ul className="repack-finder-results">
            {found.map((sku) => {
              const isSelected = chosenId === sku.skuId;
              return (
                <li key={sku.skuId} className="repack-finder-result-item">
                  <button
                    type="button"
                    className={`repack-finder-result-btn ${isSelected ? "repack-finder-result-btn--selected" : ""}`}
                    aria-pressed={isSelected}
                    onClick={() => {
                      onChoose(sku);
                      setFound(null);
                    }}
                  >
                    <div className="repack-sku-info">
                      <span className="repack-sku-code">{sku.skuCode}</span>
                      <span className="repack-sku-name">
                        {inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa)}
                      </span>
                    </div>
                    {isSelected ? (
                      <svg
                        className="repack-sku-check"
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                        focusable="false"
                      >
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    ) : (
                      <svg
                        className="repack-sku-arrow"
                        viewBox="0 0 24 24"
                        width="14"
                        height="14"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                        focusable="false"
                      >
                        <polyline points="9 18 15 12 9 6" />
                      </svg>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}

/**
 * Repacks at a location (25A section 8, "Production sheet", web; doc 25 section 3.6, flow 6.5):
 * the entity's recipes (define, retire), a repack form (a recipe, one lot of its input, the
 * quantity taken and the packs actually made, with the expected yield beside them), and the
 * repacks done here with their yield variance and pack cost, each reversible while its packs are
 * untouched.
 */
export function RepacksPage() {
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const canManage = useHasPermission("inv.recipe.manage");
  const canExecute = useHasPermission("inv.repack.execute");
  const canReverse = useHasPermission("inv.repack.reverse");
  const defineKey = useIdempotencyKey();
  const retireKey = useIdempotencyKey();
  const executeKey = useIdempotencyKey();
  const reverseKey = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [draft, setDraft] = useState({ name: "", inputSkuId: "", inputQty: "", outputSkuId: "", outputQty: "", loss: "0" });
  const [run, setRun] = useState({ recipeId: "", batchId: "", inputQty: "", actual: "", reason: "" });
  const [reversing, setReversing] = useState<string | null>(null);

  const recipes = useQuery({ queryKey: ["inventory", "recipes"], queryFn: () => api.recipes() });
  const repacks = useQuery({
    queryKey: ["inventory", "repacks", locationId],
    queryFn: () => api.repacks(locationId),
    enabled: locationId !== ""
  });
  const lots = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });
  const active = (recipes.data ?? []).filter((r) => r.status === "ACTIVE");
  const recipe = active.find((r) => r.recipeId === run.recipeId);
  const inputLots = (lots.data ?? []).filter(
    (lot) => recipe && lot.skuId === recipe.inputSkuId && lot.condition === "GOOD" && lot.qtyOnHand > 0
  );
  const inputQty = quantityOf(run.inputQty);
  const actual = quantityOf(run.actual);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["inventory"] });
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const define = useMutation({
    mutationFn: () =>
      api.defineRecipe(
        {
          name: draft.name.trim(),
          inputSkuId: draft.inputSkuId,
          inputQty: Number(draft.inputQty),
          outputSkuId: draft.outputSkuId,
          outputQty: Number(draft.outputQty),
          expectedLossPct: Number(draft.loss || "0")
        },
        defineKey.current()
      ),
    onSuccess: () => {
      defineKey.next();
      setDraft({ name: "", inputSkuId: "", inputQty: "", outputSkuId: "", outputQty: "", loss: "0" });
      refresh();
    },
    onError: forget(defineKey)
  });
  const retire = useMutation({
    mutationFn: (recipeId: string) => api.retireRecipe(recipeId, retireKey.current()),
    onSuccess: () => {
      retireKey.next();
      refresh();
    },
    onError: forget(retireKey)
  });
  const execute = useMutation({
    mutationFn: () =>
      api.executeRepack(
        {
          recipeId: run.recipeId,
          locationId,
          inputBatchId: run.batchId,
          inputQty: inputQty ?? 0,
          actualOutputQty: actual ?? 0,
          ...(run.reason.trim() === "" ? {} : { varianceReason: run.reason.trim() })
        },
        executeKey.current()
      ),
    onSuccess: () => {
      executeKey.next();
      setRun({ recipeId: "", batchId: "", inputQty: "", actual: "", reason: "" });
      refresh();
    },
    onError: forget(executeKey)
  });
  const reverse = useMutation({
    mutationFn: ({ repackId, reason }: { repackId: string; reason: string }) =>
      api.reverseRepack(repackId, reason, reverseKey.current()),
    onSuccess: () => {
      reverseKey.next();
      setReversing(null);
      refresh();
    },
    onError: forget(reverseKey)
  });

  const draftReady =
    draft.name.trim() !== "" &&
    draft.inputSkuId !== "" &&
    draft.outputSkuId !== "" &&
    Number(draft.inputQty) > 0 &&
    Number(draft.outputQty) > 0;
  const runReady = recipe !== undefined && run.batchId !== "" && (inputQty ?? 0) > 0 && (actual ?? 0) > 0;

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
      <PageHeader
        icon="repacks"
        title={t("inventory.repack.title").text}
      />

      <div className="inventory-control-links">
        <Link className="action-link" to="/inventory/counts">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M9 11l3 3L22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </svg>
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="9" y1="15" x2="15" y2="15" />
          </svg>
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link action-link--primary" to="/inventory/repacks">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="m21 16-4 4-4-4" />
            <path d="M17 20V4" />
            <path d="m3 8 4-4 4 4" />
            <path d="M7 4v16" />
          </svg>
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/transfer-requests">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <rect x="1" y="3" width="15" height="13" />
            <polygon points="16 8 20 8 23 11 23 16 16 16 8" />
            <circle cx="5.5" cy="18.5" r="2.5" />
            <circle cx="18.5" cy="18.5" r="2.5" />
          </svg>
          <span>{t("inventory.request.link").text}</span>
        </Link>
      </div>

      <section className="modern-table-card" style={{ marginBottom: 'var(--space-4)' }}>
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <path d="m21 16-4 4-4-4" />
              <path d="M17 20V4" />
              <path d="m3 8 4-4 4 4" />
              <path d="M7 4v16" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.repack.recipes").text}</h2>
        </div>
        {recipes.data?.length === 0 && <p style={{ padding: 'var(--space-3) var(--space-4)' }}>{t("inventory.repack.recipe.none").text}</p>}
        {recipes.data && recipes.data.length > 0 && (
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.repack.recipe.name").text}</th>
                  <th>{t("inventory.repack.recipe.input").text}</th>
                  <th>{t("inventory.repack.recipe.output").text}</th>
                  <th>{t("inventory.repack.recipe.loss").text}</th>
                  <th>{t("inventory.repack.column.status").text}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {recipes.data.map((r) => (
                  <tr key={r.recipeId}>
                    <td>{r.name}</td>
                    <td>
                      {`${r.inputQty} × `}
                      <SkuLabel skuId={r.inputSkuId} />
                    </td>
                    <td>
                      {`${r.outputQty} × `}
                      <SkuLabel skuId={r.outputSkuId} />
                    </td>
                    <td className="numeric-cell">{r.expectedLossPct}</td>
                    <td>{t(`inventory.repack.recipe.status.${r.status}`).text}</td>
                    <td>
                      {r.status === "ACTIVE" && canManage && (
                        <button type="button" className="table-action-btn" disabled={retire.isPending} onClick={() => retire.mutate(r.recipeId)}>
                          {t("inventory.repack.recipe.retire").text}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {canManage && (
        <section className="modern-table-card inventory-form-card" style={{ marginBottom: 'var(--space-4)' }}>
          <div className="inventory-card-header">
            <span className="inventory-card-header__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <line x1="12" y1="5" x2="12" y2="19" />
                <line x1="5" y1="12" x2="19" y2="12" />
              </svg>
            </span>
            <h2 className="inventory-card-header__title">{t("inventory.repack.recipe.new").text}</h2>
          </div>
          <div className="repack-form-body">
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.recipe.name").text}</span>
              <input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
            </label>
            <SkuFinder
              label={t("inventory.repack.find_input").text}
              buttonLabel={t("inventory.repack.find_input_button").text}
              chosenId={draft.inputSkuId}
              onChoose={(sku) => setDraft({ ...draft, inputSkuId: sku.skuId })}
            />
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.recipe.input_qty").text}</span>
              <input inputMode="decimal" value={draft.inputQty} onChange={(event) => setDraft({ ...draft, inputQty: event.target.value })} />
            </label>
            <SkuFinder
              label={t("inventory.repack.find_output").text}
              buttonLabel={t("inventory.repack.find_output_button").text}
              chosenId={draft.outputSkuId}
              onChoose={(sku) => setDraft({ ...draft, outputSkuId: sku.skuId })}
            />
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.recipe.output_qty").text}</span>
              <input inputMode="decimal" value={draft.outputQty} onChange={(event) => setDraft({ ...draft, outputQty: event.target.value })} />
            </label>
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.recipe.loss").text}</span>
              <input inputMode="decimal" value={draft.loss} onChange={(event) => setDraft({ ...draft, loss: event.target.value })} />
            </label>
            <div className="inventory-action-bar">
              <button
                type="button"
                className="inventory-submit-btn"
                disabled={!draftReady || define.isPending}
                onClick={() => define.mutate()}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                  <path d="M12 5v14M5 12h14" />
                </svg>
                <span>{t("inventory.repack.recipe.define").text}</span>
              </button>
            </div>
            {define.isError && <p role="alert">{errorText(define.error, t("inventory.error.generic").text)}</p>}
          </div>
        </section>
      )}

      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      {canExecute && locationId !== "" && (
        <section className="modern-table-card inventory-form-card" style={{ marginTop: 'var(--space-4)' }}>
          <div className="inventory-card-header">
            <span className="inventory-card-header__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="m21 16-4 4-4-4" />
                <path d="M17 20V4" />
                <path d="m3 8 4-4 4 4" />
                <path d="M7 4v16" />
              </svg>
            </span>
            <h2 className="inventory-card-header__title">{t("inventory.repack.execute_section").text}</h2>
          </div>
          <div className="repack-form-body">
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.recipe").text}</span>
              <CustomSelect
                ariaLabel={t("inventory.repack.recipe").text}
                value={run.recipeId}
                onChange={(val) => setRun({ ...run, recipeId: val, batchId: "" })}
                options={[
                  { value: "", label: "" },
                  ...active.map((r) => ({
                    value: r.recipeId,
                    label: r.name
                  }))
                ]}
              />
            </label>
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.input_lot").text}</span>
              <CustomSelect
                ariaLabel={t("inventory.repack.input_lot").text}
                value={run.batchId}
                onChange={(val) => setRun({ ...run, batchId: val })}
                options={[
                  { value: "", label: "" },
                  ...inputLots.map((lot) => ({
                    value: lot.batchId,
                    label: `${isSyntheticBatchNo(lot.batchNo) ? t("inventory.field.batch_not_tracked").text : (lot.batchNo ?? "")} · ${lot.qtyOnHand}`
                  }))
                ]}
              />
            </label>
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.input_qty").text}</span>
              <input inputMode="decimal" value={run.inputQty} onChange={(event) => setRun({ ...run, inputQty: event.target.value })} />
            </label>
            {recipe && inputQty !== null && (
              <p className="repack-yield-hint">{t("inventory.repack.expected", undefined, { qty: expectedOutputOf(recipe, inputQty) }).text}</p>
            )}
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.actual").text}</span>
              <input inputMode="decimal" value={run.actual} onChange={(event) => setRun({ ...run, actual: event.target.value })} />
            </label>
            <label className="inventory-form-field">
              <span className="repack-field-label">{t("inventory.repack.variance_reason").text}</span>
              <input value={run.reason} onChange={(event) => setRun({ ...run, reason: event.target.value })} />
            </label>
            <div className="inventory-action-bar">
              <button
                type="button"
                className="inventory-submit-btn"
                disabled={!runReady || execute.isPending}
                onClick={() => execute.mutate()}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                  <path d="m21 16-4 4-4-4" />
                  <path d="M17 20V4" />
                  <path d="m3 8 4-4 4 4" />
                  <path d="M7 4v16" />
                </svg>
                <span>{t("inventory.repack.execute").text}</span>
              </button>
            </div>
            {execute.isError && <p role="alert">{errorText(execute.error, t("inventory.error.generic").text)}</p>}
          </div>
        </section>
      )}

      <section className="modern-table-card" style={{ marginTop: 'var(--space-4)' }}>
        {locationId !== "" && (
          <div className="inventory-card-header">
            <span className="inventory-card-header__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="m21 16-4 4-4-4" />
                <path d="M17 20V4" />
                <path d="m3 8 4-4 4 4" />
                <path d="M7 4v16" />
              </svg>
            </span>
            <h2 className="inventory-card-header__title">{t("inventory.repack.list").text}</h2>
          </div>
        )}
        {repacks.data?.length === 0 && <p style={{ padding: 'var(--space-3) var(--space-4)' }}>{t("inventory.repack.none").text}</p>}
        {repacks.data && repacks.data.length > 0 && (
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.card.column.when").text}</th>
                  <th>{t("inventory.repack.recipe.input").text}</th>
                  <th>{t("inventory.repack.recipe.output").text}</th>
                  <th>{t("inventory.repack.column.output_batch").text}</th>
                  <th>{t("inventory.repack.column.variance").text}</th>
                  <th>{t("inventory.repack.column.unit_cost").text}</th>
                  <th>{t("inventory.repack.column.status").text}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {repacks.data.map((r) => (
                  <tr key={r.repackId}>
                    <td>{r.executedAt ? formatInstant(r.executedAt) : ""}</td>
                    <td>
                      {`${r.inputQty} × `}
                      <SkuLabel skuId={r.inputSkuId} />
                    </td>
                    <td>
                      {`${r.actualOutputQty} × `}
                      <SkuLabel skuId={r.outputSkuId} />
                    </td>
                    <td>{isSyntheticBatchNo(r.outputBatchNo) ? t("inventory.field.batch_not_tracked").text : (r.outputBatchNo ?? "")}</td>
                    <td className="numeric-cell">{r.varianceQty}</td>
                    <td className="numeric-cell">{r.outputUnitCost == null ? "" : <MoneyDisplay amount={r.outputUnitCost} />}</td>
                    <td>
                      <StateChip
                        state={r.status === "REVERSED" ? "void" : "issued"}
                        label={t(`inventory.repack.status.${r.status}`).text}
                      />
                    </td>
                    <td>
                      {r.status === "EXECUTED" && canReverse && (
                        <button type="button" className="table-action-btn" onClick={() => setReversing(r.repackId)}>
                          {t("inventory.repack.reverse").text}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {reversing && (
        <ReasonCapture
          title={t("inventory.repack.reverse.question").text}
          codes={["WRONG_RECIPE", "WRONG_QTY", "OTHER"].map((code) => ({
            code,
            label: t(`inventory.repack.reason.${code}`).text
          }))}
          pending={reverse.isPending}
          onCancel={() => setReversing(null)}
          onConfirm={(code, text) =>
            reverse.mutate({
              repackId: reversing,
              reason: [t(`inventory.repack.reason.${code}`).text, text].filter(Boolean).join(": ")
            })
          }
        />
      )}
      {[retire, reverse].map((m, i) =>
        m.isError ? (
          <p key={i} role="alert">
            {errorText(m.error, t("inventory.error.generic").text)}
          </p>
        ) : null
      )}
    </main>
  );
}
