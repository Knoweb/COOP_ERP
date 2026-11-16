import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useIntl } from "react-intl";
import { useMutation, useQueries, useQuery } from "@tanstack/react-query";
import { businessToday } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { entityText, inLocale, shortId, skuText } from "./labels";
import { useTradingApi, type Sku } from "./tradingApi";
import { errorText, lineAmount, orderRequest, requisitionReady, type RequisitionRow } from "./tradingView";
import { TradingSelect } from "./TradingSelect";

/**
 * The requisition book (doc 30 section 5.4; 24A section 8, "Requisition", demo scope): the buyer
 * picks a seller it trades with (an ACTIVE relationship, M1), names where the goods are to be
 * delivered (one of its own locations, CR-24A-2), adds items from the catalogue with a quantity,
 * and sees each line at the seller's tier price for that quantity (M3 ResolveTradePrice). Submit
 * drafts the order and issues it from the buyer's series in one step; if the second call is
 * refused, the draft stays and its card offers the submit again.
 */
export function NewOrderPage() {
  const t = useT();
  const api = useTradingApi();
  const navigate = useNavigate();
  const { locale } = useIntl();
  const createKey = useIdempotencyKey();
  const submitKey = useIdempotencyKey();
  const [relationshipId, setRelationshipId] = useState("");
  const [deliverTo, setDeliverTo] = useState("");
  const [rows, setRows] = useState<RequisitionRow[]>([]);

  const sellers = useQuery({ queryKey: ["trading", "sellers"], queryFn: () => api.sellers() });
  const locations = useQuery({ queryKey: ["trading", "own-locations"], queryFn: () => api.locations() });
  const relationship = sellers.data?.find((row) => row.relationshipId === relationshipId);
  const selectedLocation = locations.data?.find((l) => l.locationId === deliverTo);

  const sellerEntities = useQueries({
    queries: (sellers.data ?? []).map((row) => ({
      queryKey: ["trading", "entity", row.sellerEntityId],
      queryFn: () => api.entity(row.sellerEntityId),
      staleTime: Infinity,
      retry: false
    }))
  });

  const sellerOptions = (sellers.data ?? []).map((row, index) => {
    const entityData = sellerEntities[index]?.data;
    const label = entityData ? entityText(entityData, locale) : shortId(row.sellerEntityId);
    return {
      value: row.relationshipId,
      label
    };
  });

  const locationOptions = (locations.data ?? []).map((location) => {
    const label = `${location.locationCode} ${inLocale(locale, location.nameEn, location.nameSi, location.nameTa)}`;
    const isManual = location.connectivitySpecMet === false;
    return {
      value: location.locationId,
      label: isManual ? `${label} [${t("trading.order.manual_branch").text}]` : label
    };
  });

  const submit = useMutation({
    mutationFn: async () => {
      const draft = await api.createOrder(orderRequest(relationship!.sellerEntityId, deliverTo, rows), createKey.current());
      createKey.next();
      try {
        await api.submitOrder(draft.orderId, submitKey.current());
        submitKey.next();
      } catch (error) {
        if (error instanceof ApiProblem) {
          submitKey.next();
        }
      }
      return draft;
    },
    onSuccess: (draft) => navigate(`/trading/orders/${draft.orderId}`),
    onError: (error) => {
      if (error instanceof ApiProblem) {
        createKey.next();
      }
    }
  });

  const setQty = (index: number, qty: string) =>
    setRows((current) => current.map((row, at) => (at === index ? { ...row, qty } : row)));
  const add = (sku: Sku) =>
    setRows((current) =>
      current.some((row) => row.skuId === sku.skuId)
        ? current
        : [...current, { skuId: sku.skuId, label: skuText(sku, locale), uomCode: sku.baseUomCode, qty: "" }]
    );
  const send = (event: FormEvent) => {
    event.preventDefault();
    submit.mutate();
  };

  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/trading">
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
          {t("trading.back").text}
        </Link>
      </div>
      <PageHeader icon="trading" title={t("trading.order.new.title").text} />
      <form onSubmit={send} className="trading-order-form">
        <section className="modern-filter-panel modern-filter-panel--trading">
          <div className="trading-order-fields-row">
            <div className="trading-form-field">
              <label htmlFor="trading-seller-select" className="trading-form-field__label">
                {t("trading.field.seller").text}
              </label>
              <TradingSelect
                id="trading-seller-select"
                ariaLabel={t("trading.field.seller").text}
                value={relationshipId}
                onChange={setRelationshipId}
                placeholder={t("trading.field.choose").text}
                options={sellerOptions}
                required
              />
            </div>
            {sellers.data?.length === 0 && <p className="trading-empty-hint">{t("trading.sellers.none").text}</p>}
            <div className="trading-form-field">
              <label htmlFor="trading-deliver-select" className="trading-form-field__label">
                {t("trading.field.deliver_to").text}
              </label>
              <TradingSelect
                id="trading-deliver-select"
                ariaLabel={t("trading.field.deliver_to").text}
                value={deliverTo}
                onChange={setDeliverTo}
                placeholder={t("trading.field.choose").text}
                options={locationOptions}
                required
              />
            </div>
          </div>
          {selectedLocation && selectedLocation.connectivitySpecMet === false && (
            <aside className="trading-on-behalf-notice" role="status">
              <strong>{t("trading.order.on_behalf.badge").text}: </strong>
              {t("trading.order.on_behalf.description", undefined, {
                branch: `${selectedLocation.locationCode} ${inLocale(locale, selectedLocation.nameEn, selectedLocation.nameSi, selectedLocation.nameTa)}`
              }).text}
            </aside>
          )}
        </section>

        <section className="modern-table-card trading-order-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th>{t("trading.column.item").text}</th>
                  <th>{t("trading.column.unit").text}</th>
                  <th style={{ width: "calc(var(--space-8) * 1.5)" }}>{t("trading.column.qty").text}</th>
                  <th className="numeric-cell">{t("trading.column.tier_price").text}</th>
                  <th className="numeric-cell">{t("trading.column.amount").text}</th>
                  <th className="col-actions" />
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="trading-empty-row">
                      <div className="trading-empty-box">
                        <p className="trading-empty-box__text">{t("trading.order.lines.empty").text}</p>
                      </div>
                    </td>
                  </tr>
                ) : (
                  rows.map((row, index) => (
                    <tr key={row.skuId}>
                      <td><strong>{row.label}</strong></td>
                      <td><span className="stock-uom-chip">{row.uomCode}</span></td>
                      <td>
                        <input
                          type="number"
                          min="0"
                          step="any"
                          aria-label={t("trading.column.qty").text}
                          value={row.qty}
                          onChange={(event) => setQty(index, event.target.value)}
                          className="trading-qty-input"
                        />
                      </td>
                      <TierPriceCells relationshipId={relationshipId} row={row} />
                      <td className="col-actions">
                        <button
                          type="button"
                          className="table-action-btn table-action-btn--danger"
                          onClick={() => setRows((current) => current.filter((_, at) => at !== index))}
                        >
                          {t("trading.remove").text}
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>

        <div className="trading-order-submit-bar">
          <button
            type="submit"
            className="button button--primary"
            disabled={submit.isPending || !relationship || !deliverTo || !requisitionReady(rows)}
          >
            {t("trading.order.submit").text}
          </button>
        </div>
        {submit.isError && <p role="alert" className="trading-feedback--error">{errorText(submit.error, t("trading.error.generic").text)}</p>}
      </form>
      <SkuFinder onPick={add} />
    </main>
  );
}

/** The seller's tier price of the line for its quantity today, and the line's amount at that price. */
function TierPriceCells({ relationshipId, row }: { relationshipId: string; row: RequisitionRow }) {
  const api = useTradingApi();
  const qty = Number(row.qty);
  const today = businessToday();
  const price = useQuery({
    queryKey: ["trading", "tier-price", relationshipId, row.skuId, row.uomCode, qty, today],
    queryFn: () => api.tradePrice(relationshipId, row.skuId, row.uomCode, qty, today),
    enabled: relationshipId !== "" && qty > 0
  });
  const unit = price.data?.unitPrice;
  return (
    <>
      <td className="numeric-cell">{unit !== undefined && <MoneyDisplay amount={unit} />}</td>
      <td className="numeric-cell">{unit !== undefined && <MoneyDisplay amount={lineAmount(qty, unit)} />}</td>
    </>
  );
}

/** Finds catalogue items in use by code or name and adds the one chosen as a line. */
function SkuFinder({ onPick }: { onPick: (sku: Sku) => void }) {
  const t = useT();
  const api = useTradingApi();
  const { locale } = useIntl();
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Sku[] | null>(null);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setFound(await api.searchSkus(q.trim()));
  };
  const nameOf = (sku: Sku) => inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa);

  return (
    <section className="modern-table-card trading-finder-card">
      <div className="trading-finder-header">
        <span className="trading-finder-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
        </span>
        <h2 className="trading-finder-title">{t("trading.field.find_item").text}</h2>
      </div>

      <div className="trading-finder-body">
        <form onSubmit={search} className="trading-search-form">
          <div className="trading-search-row">
            <label className="trading-search-input-wrapper">
              <span className="visually-hidden">{t("trading.field.find_item").text}</span>
              <svg
                className="trading-search-icon"
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
                className="trading-search-input"
                aria-label={t("trading.field.find_item").text}
                value={q}
                placeholder={t("trading.field.find_item").text}
                onChange={(event) => setQ(event.target.value)}
              />
            </label>
            <button
              type="submit"
              className="button button--primary trading-search-btn"
              disabled={!q.trim()}
            >
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <span>{t("trading.find").text}</span>
            </button>
          </div>

          {found?.length === 0 && <p className="trading-empty-hint">{t("trading.find.none").text}</p>}
          {found && found.length > 0 && (
            <div className="trading-results-panel">
              <div className="trading-results-header">
                <span>{found.length} {t("trading.column.item").text}</span>
                <button
                  type="button"
                  className="trading-results-dismiss-btn"
                  onClick={() => setFound(null)}
                  aria-label={t("trading.back").text}
                  title={t("trading.back").text}
                >
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                </button>
              </div>
              <ul className="trading-list">
                {found.map((sku) => {
                  const skuName = nameOf(sku);
                  const addLabel = t("trading.add_item", undefined, { code: sku.skuCode, name: skuName }).text;
                  return (
                    <li key={sku.skuId} className="trading-result-item">
                      <div className="trading-result-info">
                        <span className="trading-sku-code-badge">{sku.skuCode}</span>
                        <span className="trading-sku-name">{skuName}</span>
                      </div>
                      <button
                        type="button"
                        className="button button--secondary trading-add-sku-btn"
                        onClick={() => onPick(sku)}
                        aria-label={addLabel}
                      >
                        <span className="trading-add-icon" aria-hidden="true">+</span>
                        <span>{addLabel}</span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
        </form>
      </div>
    </section>
  );
}
