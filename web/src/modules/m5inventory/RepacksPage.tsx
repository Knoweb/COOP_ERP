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
  return (
    <div className="inventory-search-form">
      <label className="inventory-form-field">
        {label}
        <input value={q} onChange={(event) => setQ(event.target.value)} />
      </label>
      <button type="button" disabled={q.trim() === ""} onClick={async () => setFound(await api.searchSkus(q.trim()))}>
        {buttonLabel}
      </button>
      {found?.length === 0 && <p>{t("inventory.find.none").text}</p>}
      <ul className="inventory-list">
        {(found ?? []).map((sku) => (
          <li key={sku.skuId}>
            <button type="button" aria-pressed={chosenId === sku.skuId} onClick={() => onChoose(sku)}>
              {`${sku.skuCode} ${inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa)}`}
            </button>
          </li>
        ))}
      </ul>
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
      <PageHeader
        icon="stock"
        title={t("inventory.title").text}
        actions={
          <Link className="action-link" to="/inventory">
            <span>{t("inventory.back").text}</span>
          </Link>
        }
      />

      <div className="inventory-control-links">
        <Link className="action-link" to="/inventory/counts">
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link action-link--primary" to="/inventory/repacks">
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/transfer-requests">
          <span>{t("inventory.request.link").text}</span>
        </Link>
      </div>

      <section className="modern-table-card" style={{ marginBottom: 'var(--space-4)' }}>
        <div style={{ padding: 'var(--space-4) var(--space-4) 0' }}>
          <h2>{t("inventory.repack.recipes").text}</h2>
        </div>
      {recipes.data?.length === 0 && <p style={{ padding: '0 var(--space-4) var(--space-4)' }}>{t("inventory.repack.recipe.none").text}</p>}
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
                    <button type="button" disabled={retire.isPending} onClick={() => retire.mutate(r.recipeId)}>
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
        <section className="modern-table-card inventory-section" style={{ padding: 'var(--space-4)', marginBottom: 'var(--space-4)' }}>
          <h2>{t("inventory.repack.recipe.new").text}</h2>
          <label className="inventory-form-field">
            {t("inventory.repack.recipe.name").text}
            <input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
          </label>
          <SkuFinder
            label={t("inventory.repack.find_input").text}
            buttonLabel={t("inventory.repack.find_input_button").text}
            chosenId={draft.inputSkuId}
            onChoose={(sku) => setDraft({ ...draft, inputSkuId: sku.skuId })}
          />
          <label className="inventory-form-field">
            {t("inventory.repack.recipe.input_qty").text}
            <input inputMode="decimal" value={draft.inputQty} onChange={(event) => setDraft({ ...draft, inputQty: event.target.value })} />
          </label>
          <SkuFinder
            label={t("inventory.repack.find_output").text}
            buttonLabel={t("inventory.repack.find_output_button").text}
            chosenId={draft.outputSkuId}
            onChoose={(sku) => setDraft({ ...draft, outputSkuId: sku.skuId })}
          />
          <label className="inventory-form-field">
            {t("inventory.repack.recipe.output_qty").text}
            <input inputMode="decimal" value={draft.outputQty} onChange={(event) => setDraft({ ...draft, outputQty: event.target.value })} />
          </label>
          <label className="inventory-form-field">
            {t("inventory.repack.recipe.loss").text}
            <input inputMode="decimal" value={draft.loss} onChange={(event) => setDraft({ ...draft, loss: event.target.value })} />
          </label>
          <div className="inventory-action-bar">
            <button type="button" disabled={!draftReady || define.isPending} onClick={() => define.mutate()}>
              {t("inventory.repack.recipe.define").text}
            </button>
          </div>
          {define.isError && <p role="alert">{errorText(define.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}

      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      {canExecute && locationId !== "" && (
        <section className="modern-table-card inventory-section" style={{ marginTop: 'var(--space-4)', padding: 'var(--space-4)' }}>
          <h2>{t("inventory.repack.execute_section").text}</h2>
          <label className="inventory-form-field">
            {t("inventory.repack.recipe").text}
            <select
              aria-label={t("inventory.repack.recipe").text}
              value={run.recipeId}
              onChange={(event) => setRun({ ...run, recipeId: event.target.value, batchId: "" })}
            >
              <option value="" />
              {active.map((r) => (
                <option key={r.recipeId} value={r.recipeId}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
          <label className="inventory-form-field">
            {t("inventory.repack.input_lot").text}
            <select
              aria-label={t("inventory.repack.input_lot").text}
              value={run.batchId}
              onChange={(event) => setRun({ ...run, batchId: event.target.value })}
            >
              <option value="" />
              {inputLots.map((lot) => (
                <option key={lot.stockLotId} value={lot.batchId}>
                  {`${isSyntheticBatchNo(lot.batchNo) ? t("inventory.field.batch_not_tracked").text : (lot.batchNo ?? "")} · ${lot.qtyOnHand}`}
                </option>
              ))}
            </select>
          </label>
          <label className="inventory-form-field">
            {t("inventory.repack.input_qty").text}
            <input inputMode="decimal" value={run.inputQty} onChange={(event) => setRun({ ...run, inputQty: event.target.value })} />
          </label>
          {recipe && inputQty !== null && (
            <p>{t("inventory.repack.expected", undefined, { qty: expectedOutputOf(recipe, inputQty) }).text}</p>
          )}
          <label className="inventory-form-field">
            {t("inventory.repack.actual").text}
            <input inputMode="decimal" value={run.actual} onChange={(event) => setRun({ ...run, actual: event.target.value })} />
          </label>
          <label className="inventory-form-field">
            {t("inventory.repack.variance_reason").text}
            <input value={run.reason} onChange={(event) => setRun({ ...run, reason: event.target.value })} />
          </label>
          <div className="inventory-action-bar">
            <button type="button" disabled={!runReady || execute.isPending} onClick={() => execute.mutate()}>
              {t("inventory.repack.execute").text}
            </button>
          </div>
          {execute.isError && <p role="alert">{errorText(execute.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}

      <section className="modern-table-card" style={{ marginTop: 'var(--space-4)' }}>
      {locationId !== "" && (
        <div style={{ padding: 'var(--space-4) var(--space-4) 0' }}>
          <h2>{t("inventory.repack.list").text}</h2>
        </div>
      )}
      {repacks.data?.length === 0 && <p style={{ padding: '0 var(--space-4) var(--space-4)' }}>{t("inventory.repack.none").text}</p>}
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
                    <button type="button" onClick={() => setReversing(r.repackId)}>
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
