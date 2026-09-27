import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useIntl } from "react-intl";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { EntityName, LocationName, SkuLabel, inLocale } from "./labels";
import { useTradingApi, type LotBalance } from "./tradingApi";
import { deliveryRequest, errorText, openQty, type DispatchRow } from "./tradingView";

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
        <Link to="/trading">{t("trading.back").text}</Link>
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
      <Link to={`/trading/orders/${o.orderId}`}>{t("trading.back_to_order").text}</Link>
      <h1>{t("trading.note.new.title", undefined, { number: o.docNumber ?? "" }).text}</h1>
      <dl>
        <dt>{t("trading.column.buyer").text}</dt>
        <dd>
          <EntityName entityId={o.buyerEntityId} />
        </dd>
        <dt>{t("trading.field.deliver_to").text}</dt>
        <dd>{o.deliverToLocationId ? <LocationName locationId={o.deliverToLocationId} /> : "—"}</dd>
      </dl>
      {!o.deliverToLocationId && <p role="alert">{t("trading.note.no_deliver_to").text}</p>}

      <form onSubmit={send} style={{ display: "grid", gap: "var(--space-2)" }}>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("trading.field.from_location").text}
          <select value={fromLocationId} onChange={(event) => setFromLocationId(event.target.value)} required>
            <option value="">{t("trading.field.choose").text}</option>
            {(warehouses.data ?? []).map((location) => (
              <option key={location.locationId} value={location.locationId}>
                {`${location.locationCode} ${inLocale(locale, location.nameEn, location.nameSi, location.nameTa)}`}
              </option>
            ))}
          </select>
        </label>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("trading.field.vehicle").text}
          <input value={vehicleRef} maxLength={40} onChange={(event) => setVehicleRef(event.target.value)} />
        </label>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("trading.field.driver").text}
          <input value={driverName} maxLength={120} onChange={(event) => setDriverName(event.target.value)} />
        </label>
        <table>
          <thead>
            <tr>
              <th>{t("trading.column.item").text}</th>
              <th>{t("trading.column.batch").text}</th>
              <th>{t("trading.column.qty").text}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.orderLineId}>
                <td>
                  <SkuLabel skuId={row.skuId} />
                </td>
                <td>
                  <select
                    aria-label={t("trading.column.batch").text}
                    value={row.batchId}
                    onChange={(event) => setRow(index, { batchId: event.target.value })}
                  >
                    <option value="">{t("trading.field.no_batch").text}</option>
                    {(lots.data ?? [])
                      .filter((lot) => lot.skuId === row.skuId && lot.condition === "GOOD" && lot.qtyOnHand > 0)
                      .map((lot) => (
                        <option key={lot.stockLotId} value={lot.batchId}>
                          {`${lot.batchNo ?? ""} ${lot.expiryDate ?? ""} (${lot.qtyOnHand})`}
                        </option>
                      ))}
                  </select>
                </td>
                <td>
                  <input
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
        <div>
          <button
            type="submit"
            disabled={create.isPending || !fromLocationId || !o.deliverToLocationId || !rows.some((row) => Number(row.qty) > 0)}
          >
            {t("trading.note.create").text}
          </button>
        </div>
        {create.isError && <p role="alert">{errorText(create.error, t("trading.error.generic").text)}</p>}
      </form>
    </main>
  );
}
