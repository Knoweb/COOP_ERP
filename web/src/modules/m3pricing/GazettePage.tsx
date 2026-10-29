import { useMemo, useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { ControlPrice, Sku } from "./pricingApi";
import { SkuName, SkuPicker } from "./SkuPicker";
import { controlPriceChip, controlPriceState, errorText } from "./priceListState";
import { PageHeader } from "../../shell/components/PageHeader";
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
  const today = businessToday();
  const [searchQuery, setSearchQuery] = useState("");

  const rows = useQuery({ queryKey: ["pricing", "controlPrices"], queryFn: () => api.listControlPrices() });

  const skuQueries = useQueries({
    queries: (rows.data ?? []).map((r) => ({
      queryKey: ["pricing", "sku", r.skuId],
      queryFn: () => api.getSku(r.skuId),
      staleTime: Infinity,
      enabled: Boolean(r.skuId)
    }))
  });

  const skusById = useMemo(() => {
    const map = new Map<string, Sku>();
    for (const q of skuQueries) {
      if (q.data) {
        map.set(q.data.skuId, q.data);
      }
    }
    return map;
  }, [skuQueries]);

  const filteredRows = useMemo(() => {
    const all = rows.data ?? [];
    const q = searchQuery.trim().toLowerCase();
    if (!q) return all;

    return all.filter((row) => {
      const sku = skusById.get(row.skuId);
      const matchGazetteRef = row.gazetteReference ? row.gazetteReference.toLowerCase().includes(q) : false;
      const matchSkuCode = sku?.skuCode ? sku.skuCode.toLowerCase().includes(q) : false;
      const matchNameEn = sku?.nameEn ? sku.nameEn.toLowerCase().includes(q) : false;
      const matchNameSi = sku?.nameSi ? sku.nameSi.toLowerCase().includes(q) : false;
      const matchNameTa = sku?.nameTa ? sku.nameTa.toLowerCase().includes(q) : false;
      const matchSkuId = row.skuId ? row.skuId.toLowerCase().includes(q) : false;
      const matchPrice =
        row.ceilingPrice != null
          ? String(row.ceilingPrice).toLowerCase().includes(q) || row.ceilingPrice.toFixed(2).includes(q)
          : false;
      const matchUom = row.ceilingUomCode ? row.ceilingUomCode.toLowerCase().includes(q) : false;
      return (
        matchGazetteRef ||
        matchSkuCode ||
        matchNameEn ||
        matchNameSi ||
        matchNameTa ||
        matchSkuId ||
        matchPrice ||
        matchUom
      );
    });
  }, [rows.data, searchQuery, skusById]);

  return (
    <main className="shell-page">
      <PageHeader icon="gazette" title={t("pricing.gazette.title").text} />
      <PricingTabs />
      <p className="pricing-muted">{t("pricing.gazette.intro").text}</p>

      {canEnter && <EnterForm onEntered={() => queryClient.invalidateQueries({ queryKey: ["pricing"] })} />}

      <section className="pricing-section">
        {rows.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {rows.isError && <p role="alert">{errorText(rows.error, t("pricing.error.generic").text)}</p>}
        {rows.data?.length === 0 && <p>{t("pricing.gazette.empty").text}</p>}
        {rows.data && rows.data.length > 0 && (
          <>
            <div className="pricing-gazette-toolbar">
              <div className="pricing-gazette-search-wrapper">
                <svg
                  className="pricing-gazette-search-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
                <input
                  type="search"
                  className="pricing-gazette-search-input"
                  placeholder={t("pricing.gazette.search_placeholder").text}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Escape") {
                      setSearchQuery("");
                    }
                  }}
                  aria-label={t("pricing.gazette.search_placeholder").text}
                />
                {searchQuery && (
                  <button
                    type="button"
                    className="pricing-gazette-search-clear"
                    onClick={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      setSearchQuery("");
                    }}
                    aria-label={t("pricing.search.clear").text}
                    title={t("pricing.search.clear").text}
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <line x1="18" y1="6" x2="6" y2="18" />
                      <line x1="6" y1="6" x2="18" y2="18" />
                    </svg>
                  </button>
                )}
              </div>
            </div>

            <div className="modern-table-card pricing-gazette-table-card">
              <div className="modern-table-scroll pricing-gazette-table-scroll">
                <table className="modern-table pricing-gazette-table">
                  <colgroup>
                    <col className="pricing-gazette-col-item" />
                    <col className="pricing-gazette-col-price" />
                    <col className="pricing-gazette-col-unit" />
                    <col className="pricing-gazette-col-from" />
                    <col className="pricing-gazette-col-to" />
                    <col className="pricing-gazette-col-ref" />
                    <col className="pricing-gazette-col-status" />
                    {canEnter && <col className="pricing-gazette-col-actions" />}
                  </colgroup>
                  <thead>
                    <tr>
                      <th className="pricing-gazette-th-item">{t("pricing.column.item").text}</th>
                      <th className="pricing-gazette-th-price">{t("pricing.column.ceiling_price").text}</th>
                      <th className="pricing-gazette-th-unit">{t("pricing.column.unit").text}</th>
                      <th className="pricing-gazette-th-from">{t("pricing.column.from").text}</th>
                      <th className="pricing-gazette-th-to">{t("pricing.column.to").text}</th>
                      <th className="pricing-gazette-th-ref">{t("pricing.column.gazette").text}</th>
                      <th className="pricing-gazette-th-status">{t("pricing.column.status").text}</th>
                      {canEnter && <th className="pricing-gazette-th-actions" />}
                    </tr>
                  </thead>
                  <tbody>
                    {filteredRows.length === 0 ? (
                      <tr>
                        <td colSpan={canEnter ? 8 : 7} className="pricing-cell-empty-search">
                          <p>{t("pricing.gazette.no_match").text}</p>
                        </td>
                      </tr>
                    ) : (
                      filteredRows.map((row) => {
                        const state = controlPriceState(row, today);
                        return (
                          <tr key={row.controlPriceId}>
                            <td className="pricing-cell-item pricing-gazette-cell-item">
                              <SkuName skuId={row.skuId} />
                            </td>
                            <td className="pricing-cell-numeric pricing-gazette-cell-price">
                              <MoneyDisplay amount={row.ceilingPrice} />
                            </td>
                            <td className="pricing-cell-center pricing-gazette-cell-unit">{row.ceilingUomCode}</td>
                            <td className="pricing-cell-center pricing-gazette-cell-from">{formatDate(row.effectiveFrom)}</td>
                            <td className="pricing-cell-center pricing-gazette-cell-to">{row.effectiveTo ? formatDate(row.effectiveTo) : ""}</td>
                            <td className="pricing-cell-center pricing-gazette-cell-ref">{row.gazetteReference}</td>
                            <td className="pricing-cell-center pricing-gazette-cell-status">
                              <StateChip state={controlPriceChip(state)} label={t(`pricing.gazette.state.${state}`).text} />
                            </td>
                            {canEnter && (
                              <td className="pricing-cell-center pricing-gazette-cell-actions">
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
                      })
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </>
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
  const [from, setFrom] = useState(businessToday());
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
  const [lastDay, setLastDay] = useState(businessToday());
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
