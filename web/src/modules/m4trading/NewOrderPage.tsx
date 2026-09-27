import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useIntl } from "react-intl";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { EntityOption, inLocale } from "./labels";
import { useTradingApi, type Sku } from "./tradingApi";
import { businessToday, errorText, lineAmount, orderRequest, requisitionReady, type RequisitionRow } from "./tradingView";

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
        : [...current, { skuId: sku.skuId, label: `${sku.skuCode} ${sku.nameEn}`, uomCode: sku.baseUomCode, qty: "" }]
    );
  const send = (event: FormEvent) => {
    event.preventDefault();
    submit.mutate();
  };

  return (
    <main className="shell-page">
      <Link to="/trading">{t("trading.back").text}</Link>
      <h1>{t("trading.order.new.title").text}</h1>
      <form onSubmit={send} style={{ display: "grid", gap: "var(--space-2)" }}>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("trading.field.seller").text}
          <select value={relationshipId} onChange={(event) => setRelationshipId(event.target.value)} required>
            <option value="">{t("trading.field.choose").text}</option>
            {(sellers.data ?? []).map((row) => (
              <EntityOption key={row.relationshipId} value={row.relationshipId} entityId={row.sellerEntityId} />
            ))}
          </select>
        </label>
        {sellers.data?.length === 0 && <p>{t("trading.sellers.none").text}</p>}
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("trading.field.deliver_to").text}
          <select value={deliverTo} onChange={(event) => setDeliverTo(event.target.value)} required>
            <option value="">{t("trading.field.choose").text}</option>
            {(locations.data ?? []).map((location) => (
              <option key={location.locationId} value={location.locationId}>
                {`${location.locationCode} ${inLocale(locale, location.nameEn, location.nameSi, location.nameTa)}`}
              </option>
            ))}
          </select>
        </label>

        <table>
          <thead>
            <tr>
              <th>{t("trading.column.item").text}</th>
              <th>{t("trading.column.unit").text}</th>
              <th>{t("trading.column.qty").text}</th>
              <th>{t("trading.column.tier_price").text}</th>
              <th>{t("trading.column.amount").text}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.skuId}>
                <td>{row.label}</td>
                <td>{row.uomCode}</td>
                <td>
                  <input
                    type="number"
                    min="0"
                    step="any"
                    aria-label={t("trading.column.qty").text}
                    value={row.qty}
                    onChange={(event) => setQty(index, event.target.value)}
                  />
                </td>
                <TierPriceCells relationshipId={relationshipId} row={row} />
                <td>
                  <button type="button" onClick={() => setRows((current) => current.filter((_, at) => at !== index))}>
                    {t("trading.remove").text}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p>{t("trading.order.lines.empty").text}</p>}
        <div>
          <button type="submit" disabled={submit.isPending || !relationship || !deliverTo || !requisitionReady(rows)}>
            {t("trading.order.submit").text}
          </button>
        </div>
        {submit.isError && <p role="alert">{errorText(submit.error, t("trading.error.generic").text)}</p>}
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
      <td>{unit !== undefined && <MoneyDisplay amount={unit} />}</td>
      <td>{unit !== undefined && <MoneyDisplay amount={lineAmount(qty, unit)} />}</td>
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
    <form onSubmit={search} style={{ display: "grid", gap: "var(--space-1)", marginTop: "var(--space-3)" }}>
      <label style={{ display: "grid", gap: "var(--space-half)" }}>
        {t("trading.field.find_item").text}
        <input type="search" value={q} onChange={(event) => setQ(event.target.value)} />
      </label>
      <div>
        <button type="submit" disabled={!q.trim()}>
          {t("trading.find").text}
        </button>
      </div>
      {found?.length === 0 && <p>{t("trading.find.none").text}</p>}
      {found && found.length > 0 && (
        <ul style={{ listStyle: "none", padding: 0 }}>
          {found.map((sku) => (
            <li key={sku.skuId}>
              <button type="button" onClick={() => onPick(sku)}>
                {t("trading.add_item", undefined, { code: sku.skuCode, name: nameOf(sku) }).text}
              </button>
            </li>
          ))}
        </ul>
      )}
    </form>
  );
}
