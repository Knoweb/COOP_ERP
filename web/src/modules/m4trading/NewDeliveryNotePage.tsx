import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useIntl } from "react-intl";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { EntityName, LocationName, SkuLabel, inLocale } from "./labels";
import { useTradingApi, type LotBalance } from "./tradingApi";
import { deliveryRequest, errorText, isSyntheticBatchNo, openQty, type DispatchRow } from "./tradingView";

/** The lot a line is sent from by default: the first GOOD lot of the item in FEFO order (M5). */
function firstLot(lots: LotBalance[], skuId: string): LotBalance | undefined {
  return lots
    .filter((lot) => lot.skuId === skuId && lot.condition === "GOOD" && lot.qtyOnHand > 0)
    .sort((a, b) => (a.fefoRank ?? 0) - (b.fefoRank ?? 0))[0];
}

/**
 * Draft the delivery note of an accepted order (24A section 6 CreateDeliveryNote; section 8,
 * "Dispatch", demo scope): one drop to the buyer's delivery location named on the order, billed
 * to the buyer; per line the quantity still open and the batch it leaves from, the first lot in
 * FEFO order at the chosen warehouse. The note's card follows, where it is issued (the stock is
 * reserved) and dispatched.
 */
export function NewDeliveryNotePage() {
  const [params] = useSearchParams();
  const orderId = params.get("orderId") ?? "";
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();
  const navigate = useNavigate();
  const { locale } = useIntl();
  const key = useIdempotencyKey();
  const [fromLocationId, setFromLocationId] = useState("");
  const [vehicleRef, setVehicleRef] = useState("");
  const [driverName, setDriverName] = useState("");
  const [rows, setRows] = useState<DispatchRow[]>([]);

  const order = useQuery({ queryKey: ["trading", "order", orderId], queryFn: () => api.order(orderId), enabled: orderId !== "" });
  const warehouses = useQuery({ queryKey: ["trading", "own-locations", "WAREHOUSE"], queryFn: () => api.locations("WAREHOUSE") });
  const lots = useQuery({
    queryKey: ["trading", "balances", fromLocationId],
    queryFn: () => api.balances(fromLocationId),
    enabled: fromLocationId !== ""
  });

  // One warehouse: take it. The lines start at what is still open, from the first FEFO lot.
  useEffect(() => {
    if (!fromLocationId && warehouses.data?.length === 1) {
      setFromLocationId(warehouses.data[0].locationId);
    }
  }, [warehouses.data, fromLocationId]);
  useEffect(() => {
    if (order.data) {
      setRows(
        order.data.lines
          .filter((line) => openQty(line) > 0)
          .map((line) => ({
            orderLineId: line.lineId,
            skuId: line.skuId,
            qty: String(openQty(line)),
            batchId: firstLot(lots.data ?? [], line.skuId)?.batchId ?? ""
          }))
      );
    }
  }, [order.data, lots.data]);

  const create = useMutation({
    mutationFn: () => api.createDeliveryNote(deliveryRequest(order.data!, fromLocationId, vehicleRef, driverName, rows), key.current()),
    onSuccess: (note) => {
      key.next();
      navigate(`/trading/delivery-notes/${note.deliveryNoteId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  if (order.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (order.isError || !order.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(order.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      </main>
    );
  }

  const o = order.data;
  const setRow = (index: number, change: Partial<DispatchRow>) =>
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...change } : row)));
  const send = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <main className="shell-page">
      <Link className="back-link" to={`/trading/orders/${o.orderId}`}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m12 19-7-7 7-7" />
          <path d="M19 12H5" />
        </svg>
        {t("trading.back_to_order").text}
      </Link>
      <DocumentHeader
        code={o.docNumber ?? t("trading.order.draft_number").text}
        title={t("trading.note.new.title", undefined, { number: o.docNumber ?? "" }).text}
        state={{ look: "draft", label: t("trading.note.status.DRAFT").text }}
        facts={[
          { label: t("trading.column.buyer").text, value: <EntityName entityId={o.buyerEntityId} /> },
          {
            label: t("trading.field.deliver_to").text,
            value: o.deliverToLocationId ? (
              <LocationName locationId={o.deliverToLocationId} own={false} known={o.deliverTo} />
            ) : undefined
          }
        ]}
      />
      {!o.deliverToLocationId && <p role="alert">{t("trading.note.no_deliver_to").text}</p>}

      <form onSubmit={send} className="trading-dispatch-form">
        <section className="trading-dispatch-card">
          <div className="trading-dispatch-card__header">
            <div className="trading-dispatch-card__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="1" y="3" width="15" height="13" />
                <polygon points="16 8 20 8 23 11 23 16 16 16 8" />
                <circle cx="5.5" cy="18.5" r="2.5" />
                <circle cx="18.5" cy="18.5" r="2.5" />
              </svg>
            </div>
            <div className="trading-dispatch-card__titles">
              <h2 className="trading-dispatch-card__title">{t("trading.note.dispatch").text}</h2>
            </div>
          </div>

          <div className="trading-dispatch-card__grid">
            <label className="trading-form-field">
              <span className="trading-form-field__label">{t("trading.field.from_location").text}</span>
              <select value={fromLocationId} onChange={(event) => setFromLocationId(event.target.value)} required>
                <option value="">{t("trading.field.choose").text}</option>
                {(warehouses.data ?? []).map((location) => (
                  <option key={location.locationId} value={location.locationId}>
                    {`${location.locationCode} ${inLocale(locale, location.nameEn, location.nameSi, location.nameTa)}`}
                  </option>
                ))}
              </select>
            </label>
            <label className="trading-form-field">
              <span className="trading-form-field__label">{t("trading.field.vehicle").text}</span>
              <input
                type="text"
                placeholder={t("trading.field.vehicle").text}
                value={vehicleRef}
                maxLength={40}
                onChange={(event) => setVehicleRef(event.target.value)}
              />
            </label>
            <label className="trading-form-field">
              <span className="trading-form-field__label">{t("trading.field.driver").text}</span>
              <input
                type="text"
                placeholder={t("trading.field.driver").text}
                value={driverName}
                maxLength={120}
                onChange={(event) => setDriverName(event.target.value)}
              />
            </label>
          </div>
        </section>

        <section className="trading-items-card">
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th scope="col">{t("trading.column.item").text}</th>
                    <th scope="col">{t("trading.column.batch").text}</th>
                    <th scope="col" className="trading-col-qty">{t("trading.column.qty").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, index) => (
                    <tr key={row.orderLineId}>
                      <td className="trading-item-cell">
                        <SkuLabel skuId={row.skuId} />
                      </td>
                      <td className="trading-batch-cell">
                        <select
                          className="trading-table-select"
                          aria-label={t("trading.column.batch").text}
                          value={row.batchId}
                          onChange={(event) => setRow(index, { batchId: event.target.value })}
                        >
                          <option value="">{t("trading.field.no_batch").text}</option>
                          {(lots.data ?? [])
                            .filter((lot) => lot.skuId === row.skuId && lot.condition === "GOOD" && lot.qtyOnHand > 0)
                            .map((lot) => (
                              <option key={lot.stockLotId} value={lot.batchId}>
                                {`${isSyntheticBatchNo(lot.batchNo) ? t("trading.field.batch_not_tracked").text : (lot.batchNo ?? "")} ${lot.expiryDate ? formatDate(lot.expiryDate) : ""} (${lot.qtyOnHand})`}
                              </option>
                            ))}
                        </select>
                      </td>
                      <td className="trading-qty-cell">
                        <input
                          className="trading-table-input"
                          type="number"
                          min="0"
                          step="any"
                          aria-label={t("trading.column.qty").text}
                          value={row.qty}
                          onChange={(event) => setRow(index, { qty: event.target.value })}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </section>

        <div className="trading-form-footer">
          <button
            type="submit"
            className="trading-submit-btn"
            disabled={
              create.isPending || !fromLocationId || !lots.isSuccess || !o.deliverToLocationId || !rows.some((row) => Number(row.qty) > 0)
            }
          >
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
            </svg>
            <span>{t("trading.note.create").text}</span>
          </button>
        </div>
        {create.isError && <p role="alert">{errorText(create.error, t("trading.error.generic").text)}</p>}
      </form>
    </main>
  );
}
