import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { SetLinesResponse, Sku } from "./pricingApi";
import { SkuName, SkuPicker } from "./SkuPicker";
import { ListRelationships } from "./ListRelationships";
import { chipOf, errorText, reasonMessageId } from "./priceListState";
import "./pricing.css";

/** A line as the author edits it: numbers stay text until the server reads them. */
type Row = { skuId: string; uomCode: string; tierFromQty: string; price: string };

/**
 * One version of a trade price list (doc 30 section 5.3; 23A section 8, "Wholesale price list"):
 * its lines with volume tiers, and, for a draft, the editor: add an item from the catalogue, set
 * tiers and prices, save (the server answers each line; a refused line shows its reason inline),
 * then publish from a date, which asks for the second factor. A published version offers the
 * next draft. Prices are shown as the server sends them; the screen never computes one.
 */
export function TradePriceListPage() {
  const { listId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canAuthor = useHasPermission("prc.pricelist.author");
  const canPublish = useHasPermission("prc.pricelist.publish");
  const saveKey = useIdempotencyKey();
  const publishKey = useIdempotencyKey();
  const versionKey = useIdempotencyKey();

  const detail = useQuery({
    queryKey: ["pricing", "priceList", listId],
    queryFn: () => api.getPriceList(listId)
  });

  const [rows, setRows] = useState<Row[]>([]);
  const [outcomes, setOutcomes] = useState<SetLinesResponse | null>(null);
  const [applyFrom, setApplyFrom] = useState("");
  const [searchQuery, setSearchQuery] = useState("");

  useEffect(() => {
    if (detail.data) {
      setRows(
        detail.data.lines.map((line) => ({
          skuId: line.skuId,
          uomCode: line.uomCode,
          tierFromQty: String(line.tierFromQty),
          price: String(line.price)
        }))
      );
    }
  }, [detail.data]);

  const skuQueries = useQueries({
    queries: rows.map((r) => ({
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
    const q = searchQuery.trim().toLowerCase();
    const indexed = rows.map((row, originalIndex) => ({ row, originalIndex }));
    if (!q) return indexed;

    return indexed.filter(({ row }) => {
      const sku = skusById.get(row.skuId);
      const matchCode = sku?.skuCode ? sku.skuCode.toLowerCase().includes(q) : false;
      const matchNameEn = sku?.nameEn ? sku.nameEn.toLowerCase().includes(q) : false;
      const matchNameSi = sku?.nameSi ? sku.nameSi.toLowerCase().includes(q) : false;
      const matchNameTa = sku?.nameTa ? sku.nameTa.toLowerCase().includes(q) : false;
      const matchSkuId = row.skuId ? row.skuId.toLowerCase().includes(q) : false;
      const matchUom = row.uomCode ? row.uomCode.toLowerCase().includes(q) : false;
      const matchTier = row.tierFromQty ? row.tierFromQty.toLowerCase().includes(q) : false;
      const matchPrice = row.price ? row.price.toLowerCase().includes(q) : false;
      return (
        matchCode ||
        matchNameEn ||
        matchNameSi ||
        matchNameTa ||
        matchSkuId ||
        matchUom ||
        matchTier ||
        matchPrice
      );
    });
  }, [rows, searchQuery, skusById]);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["pricing"] });
  };
  const forgetKeyOnProblem = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };

  const save = useMutation({
    mutationFn: () =>
      api.setLines(
        listId,
        rows.map((row) => ({
          skuId: row.skuId,
          uomCode: row.uomCode,
          tierFromQty: Number(row.tierFromQty),
          price: Number(row.price)
        })),
        saveKey.current()
      ),
    onSuccess: (result) => {
      saveKey.next();
      setOutcomes(result);
      if (result.saved) {
        refresh();
      }
    },
    onError: forgetKeyOnProblem(saveKey)
  });

  const publish = useMutation({
    mutationFn: () => api.publish(listId, applyFrom, publishKey.current()),
    onSuccess: () => {
      publishKey.next();
      refresh();
    },
    onError: forgetKeyOnProblem(publishKey)
  });

  const nextVersion = useMutation({
    mutationFn: () => api.draftNewVersion(listId, versionKey.current()),
    onSuccess: (draft) => {
      versionKey.next();
      refresh();
      navigate(`/pricing/lists/${draft.priceListId}`);
    },
    onError: forgetKeyOnProblem(versionKey)
  });

  if (detail.isLoading) {
    return <main className="shell-page">{t("pricing.list.loading").text}</main>;
  }
  if (detail.isError || !detail.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(detail.error, t("pricing.error.not_found").text)}</p>
        <Link className="back-link" to="/pricing">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M15 18l-6-6 6-6" />
            <path d="M9 12h10" />
          </svg>
          <span>{t("pricing.back").text}</span>
        </Link>
      </main>
    );
  }

  const list = detail.data.list;
  const editable = list.status === "DRAFT" && canAuthor;
  const setRow = (index: number, change: Partial<Row>) => {
    setOutcomes(null);
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...change } : row)));
  };
  const submitPublish = (event: FormEvent) => {
    event.preventDefault();
    publish.mutate();
  };

  return (
    <main className="shell-page">
      <Link className="back-link" to="/pricing">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        <span>{t("pricing.back").text}</span>
      </Link>

      <div className="pricing-header-card">
        <h1>{list.name}</h1>
        <div className="pricing-header-row">
          <span className="pricing-meta-badge">{t("pricing.version", undefined, { version: list.version }).text}</span>
          <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
          {list.applyFrom && <span className="pricing-meta-badge">{t("pricing.applies_from", undefined, { date: formatDate(list.applyFrom) }).text}</span>}
        </div>
      </div>

      <div className="pricing-tradelist-toolbar">
        <div className="pricing-tradelist-search-wrapper">
          <svg
            className="pricing-tradelist-search-icon"
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
            className="pricing-tradelist-search-input"
            placeholder={t("pricing.tradelist.search_placeholder").text}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                setSearchQuery("");
              }
            }}
            aria-label={t("pricing.tradelist.search_placeholder").text}
          />
          {searchQuery && (
            <button
              type="button"
              className="pricing-tradelist-search-clear"
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

      <div className="modern-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table">
            <thead>
              <tr>
                <th>{t("pricing.column.item").text}</th>
                <th>{t("pricing.column.unit").text}</th>
                <th className="pricing-cell-numeric">{t("pricing.column.tier").text}</th>
                <th className="pricing-cell-numeric">{t("pricing.column.price").text}</th>
                {editable && <th>{t("pricing.column.outcome").text}</th>}
              </tr>
            </thead>
            <tbody>
              {filteredRows.length === 0 ? (
                <tr>
                  <td colSpan={editable ? 5 : 4} className="pricing-cell-empty-search">
                    <p>{t("pricing.tradelist.no_match").text}</p>
                  </td>
                </tr>
              ) : (
                filteredRows.map(({ row, originalIndex }) => {
                  const outcome = outcomes?.outcomes[originalIndex];
                  return (
                    <tr key={originalIndex}>
                      <td className="pricing-cell-item">
                        <SkuName skuId={row.skuId} />
                      </td>
                      <td>{row.uomCode}</td>
                      <td className="pricing-cell-numeric">
                        {editable ? (
                          <input
                            type="number"
                            min="0"
                            step="any"
                            className="pricing-table-input"
                            aria-label={t("pricing.column.tier").text}
                            value={row.tierFromQty}
                            onChange={(event) => setRow(originalIndex, { tierFromQty: event.target.value })}
                          />
                        ) : (
                          row.tierFromQty
                        )}
                      </td>
                      <td className="pricing-cell-numeric">
                        {editable ? (
                          <input
                            type="number"
                            min="0"
                            step="any"
                            className="pricing-table-input"
                            aria-label={t("pricing.column.price").text}
                            value={row.price}
                            onChange={(event) => setRow(originalIndex, { price: event.target.value })}
                          />
                        ) : (
                          <MoneyDisplay amount={row.price} />
                        )}
                      </td>
                      {editable && (
                        <td>
                          <div className="pricing-outcome-cell">
                            {outcome && !outcome.ok && outcome.reason && (
                              <span role="alert" className="pricing-alert-pill">
                                {t(reasonMessageId(outcome.reason)).text}
                              </span>
                            )}
                            {outcome?.review && <span role="note" className="pricing-note-pill">{t(reasonMessageId(outcome.review)).text}</span>}
                            <button
                              type="button"
                              className="pricing-btn-remove"
                              onClick={() => setRows((current) => current.filter((_, at) => at !== originalIndex))}
                            >
                              {t("pricing.remove").text}
                            </button>
                          </div>
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
      {rows.length === 0 && !searchQuery && (
        <div className="pricing-card pricing-empty-card">
          <p className="pricing-muted">{t("pricing.lines.empty").text}</p>
        </div>
      )}

      {editable && (
        <section className="pricing-section">
          <SkuPicker onPick={(sku) => setRows((current) => [...current, { skuId: sku.skuId, uomCode: sku.baseUomCode, tierFromQty: "0", price: "" }])} />
          <div className="pricing-card pricing-actions-card">
            <div className="pricing-actions-row">
              <button
                type="button"
                className="modern-btn modern-btn--primary"
                disabled={save.isPending}
                onClick={() => save.mutate()}
              >
                {t("pricing.save").text}
              </button>
              {outcomes?.saved && <span role="status" className="pricing-alert-box pricing-alert-box--success">{t("pricing.saved").text}</span>}
              {outcomes && !outcomes.saved && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{t("pricing.not_saved").text}</span>}
              {save.isError && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(save.error, t("pricing.error.generic").text)}</span>}
            </div>

            {canPublish && (
              <form onSubmit={submitPublish} className="pricing-publish-form">
                <label className="modern-field">
                  <span className="modern-field__label pricing-filter-label">{t("pricing.field.apply_from").text}</span>
                  <input
                    type="date"
                    required
                    className="pricing-input"
                    value={applyFrom}
                    onChange={(event) => setApplyFrom(event.target.value)}
                  />
                </label>
                <button
                  type="submit"
                  className="modern-btn modern-btn--primary"
                  disabled={publish.isPending || !applyFrom || rows.length === 0}
                >
                  {t("pricing.publish").text}
                </button>
                {publish.isError && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(publish.error, t("pricing.error.generic").text)}</span>}
              </form>
            )}
          </div>
        </section>
      )}

      {list.status === "PUBLISHED" && canAuthor && (
        <div className="pricing-card pricing-actions-card">
          <div className="pricing-actions-row">
            <button
              type="button"
              className="modern-btn modern-btn--primary"
              disabled={nextVersion.isPending}
              onClick={() => nextVersion.mutate()}
            >
              {t("pricing.next_version").text}
            </button>
            {nextVersion.isError && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(nextVersion.error, t("pricing.error.generic").text)}</span>}
          </div>
        </div>
      )}

      {list.kind === "TRADE" && <ListRelationships list={list} />}
    </main>
  );
}
