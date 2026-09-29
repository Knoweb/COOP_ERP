import { useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { ControlPrice, Sku } from "./pricingApi";
import { SkuName, SkuPicker } from "./SkuPicker";
import { controlPriceChip, controlPriceState, errorText, isoToday } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

/**
 * The gazette (23A section 8, "Gazette"; doc 23 section 4.3 and flow 6.4): every control price, the
 * history kept (a later gazette closes the earlier one of the same item the day before; a
 * rescission ends it), readable by everybody. The Federation's pricing officer enters a ceiling
 * for an item from a date with the gazette's reference, and rescinds one; both ask for the second
 * factor. A shelf price above a ceiling in force is refused, and the till never charges above it.
 */
export function GazettePage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const queryClient = useQueryClient();
  const canEnter = useHasPermission("prc.controlprice.enter");
  const today = isoToday();

  const rows = useQuery({ queryKey: ["pricing", "controlPrices"], queryFn: () => api.listControlPrices() });

  return (
    <main className="shell-page">
      <h1>{t("pricing.gazette.title").text}</h1>
      <PricingTabs />
      <p className="pricing-muted">{t("pricing.gazette.intro").text}</p>

      {canEnter && <EnterForm onEntered={() => queryClient.invalidateQueries({ queryKey: ["pricing"] })} />}

      <section className="pricing-section">
        {rows.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {rows.isError && <p role="alert">{errorText(rows.error, t("pricing.error.generic").text)}</p>}
        {rows.data?.length === 0 && <p>{t("pricing.gazette.empty").text}</p>}
        {rows.data && rows.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.column.item").text}</th>
                    <th>{t("pricing.column.ceiling_price").text}</th>
                    <th>{t("pricing.column.unit").text}</th>
                    <th>{t("pricing.column.from").text}</th>
                    <th>{t("pricing.column.to").text}</th>
                    <th>{t("pricing.column.gazette").text}</th>
                    <th>{t("pricing.column.status").text}</th>
                    {canEnter && <th />}
                  </tr>
                </thead>
                <tbody>
                  {rows.data.map((row) => {
                    const state = controlPriceState(row, today);
                    return (
                      <tr key={row.controlPriceId}>
                        <td>
                          <SkuName skuId={row.skuId} />
                        </td>
                        <td>
                          <MoneyDisplay amount={row.ceilingPrice} />
                        </td>
                        <td>{row.ceilingUomCode}</td>
                        <td>{formatDate(row.effectiveFrom)}</td>
                        <td>{row.effectiveTo ? formatDate(row.effectiveTo) : ""}</td>
                        <td>{row.gazetteReference}</td>
                        <td>
                          <StateChip state={controlPriceChip(state)} label={t(`pricing.gazette.state.${state}`).text} />
                        </td>
                        {canEnter && (
                          <td>
                            {state !== "ended" && (
                              <RescindForm
                                row={row}
                                onRescinded={() => queryClient.invalidateQueries({ queryKey: ["pricing"] })}
                              />
                            )}
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

/** Enter a control price: the item, the ceiling in its base unit, the first (and last) day, the gazette. */
function EnterForm({ onEntered }: { onEntered: () => void }) {
  const t = useT();
  const api = usePricingApi();
  const key = useIdempotencyKey();
  const [sku, setSku] = useState<Sku | null>(null);
  const [ceiling, setCeiling] = useState("");
  const [from, setFrom] = useState(isoToday());
  const [to, setTo] = useState("");
  const [gazette, setGazette] = useState("");

  const enter = useMutation({
    mutationFn: () =>
      api.enterControlPrice(
        {
          skuId: sku!.skuId,
          ceilingPrice: Number(ceiling),
          ceilingUomCode: sku!.baseUomCode,
          effectiveFrom: from,
          effectiveTo: to || null,
          gazetteReference: gazette.trim()
        },
        key.current()
      ),
    onSuccess: () => {
      key.next();
      setSku(null);
      setCeiling("");
      setTo("");
      setGazette("");
      onEntered();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    enter.mutate();
  };

  return (
    <section className="pricing-section" aria-labelledby="pricing-gazette-enter">
      <h2 id="pricing-gazette-enter">{t("pricing.gazette.enter").text}</h2>
      {sku ? (
        <p className="pricing-header-row">
          <SkuName skuId={sku.skuId} />
          <button type="button" onClick={() => setSku(null)}>
            {t("pricing.gazette.change_item").text}
          </button>
        </p>
      ) : (
        <SkuPicker onPick={setSku} />
      )}
      <form onSubmit={submit} className="pricing-form-grid">
        <label className="pricing-form-field">
          {t("pricing.column.ceiling_price").text}
          <input type="number" min="0.01" step="0.01" required value={ceiling} onChange={(e) => setCeiling(e.target.value)} />
        </label>
        <label className="pricing-form-field">
          {t("pricing.column.from").text}
          <input type="date" required value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="pricing-form-field">
          {t("pricing.gazette.field.to").text}
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="pricing-form-field">
          {t("pricing.column.gazette").text}
          <input type="text" required maxLength={80} value={gazette} onChange={(e) => setGazette(e.target.value)} />
        </label>
        <button type="submit" disabled={enter.isPending || !sku || !ceiling || !from || !gazette.trim()}>
          {t("pricing.gazette.submit").text}
        </button>
      </form>
      {enter.isSuccess && <p role="status">{t("pricing.gazette.entered").text}</p>}
      {enter.isError && <p role="alert">{errorText(enter.error, t("pricing.error.generic").text)}</p>}
    </section>
  );
}

/** Rescind a control price: its last day, why, and the gazette that withdraws it. */
function RescindForm({ row, onRescinded }: { row: ControlPrice; onRescinded: () => void }) {
  const t = useT();
  const api = usePricingApi();
  const key = useIdempotencyKey();
  const [open, setOpen] = useState(false);
  const [lastDay, setLastDay] = useState(isoToday());
  const [reason, setReason] = useState("");
  const [gazette, setGazette] = useState("");

  const rescind = useMutation({
    mutationFn: () =>
      api.rescindControlPrice(row.controlPriceId, { lastDay, reason: reason.trim(), gazetteReference: gazette.trim() }, key.current()),
    onSuccess: () => {
      key.next();
      setOpen(false);
      onRescinded();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)}>
        {t("pricing.gazette.rescind").text}
      </button>
    );
  }
  const submit = (event: FormEvent) => {
    event.preventDefault();
    rescind.mutate();
  };
  return (
    <form onSubmit={submit} className="pricing-form-grid">
      <label className="pricing-form-field">
        {t("pricing.gazette.field.last_day").text}
        <input type="date" required value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
      </label>
      <label className="pricing-form-field">
        {t("pricing.gazette.field.reason").text}
        <input type="text" required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <label className="pricing-form-field">
        {t("pricing.column.gazette").text}
        <input type="text" required maxLength={80} value={gazette} onChange={(e) => setGazette(e.target.value)} />
      </label>
      <button type="submit" disabled={rescind.isPending || !reason.trim() || !gazette.trim()}>
        {t("pricing.gazette.rescind_submit").text}
      </button>
      <button type="button" onClick={() => setOpen(false)}>
        {t("pricing.cancel").text}
      </button>
      {rescind.isError && <p role="alert">{errorText(rescind.error, t("pricing.error.generic").text)}</p>}
    </form>
  );
}
