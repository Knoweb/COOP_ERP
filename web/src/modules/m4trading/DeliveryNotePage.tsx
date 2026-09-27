import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { EntityName, LocationName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { deliveryChip, errorText } from "./tradingView";

/**
 * One delivery note (24A section 6 and 8, "Dispatch", demo scope). The seller issues the draft,
 * which takes the seller's number and reserves the stock at the warehouse it leaves from (M5
 * applies delivery_note.issued.v1), then dispatches it with the vehicle and the driver: the goods
 * are in transit. The buyer's receiver sees the note once issued and opens the goods received
 * note of each drop from here.
 */
export function DeliveryNotePage() {
  const { deliveryNoteId = "" } = useParams();
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = useTradingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const canIssue = useHasPermission("del.note.issue");
  const canDispatch = useHasPermission("del.note.dispatch");
  const canReceive = useHasPermission("shop.grn.confirm");
  const issueKey = useIdempotencyKey();
  const dispatchKey = useIdempotencyKey();
  const [vehicleRef, setVehicleRef] = useState("");
  const [driverName, setDriverName] = useState("");

  const note = useQuery({ queryKey: ["trading", "note", deliveryNoteId], queryFn: () => api.deliveryNote(deliveryNoteId) });
  useEffect(() => {
    if (note.data) {
      setVehicleRef((current) => current || note.data.vehicleRef || "");
      setDriverName((current) => current || note.data.driverName || "");
    }
  }, [note.data]);

  const after = (key: { next: () => void }) => ({
    onSuccess: () => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (error: unknown) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const issue = useMutation({ mutationFn: () => api.issueDeliveryNote(deliveryNoteId, issueKey.current()), ...after(issueKey) });
  const dispatch = useMutation({
    mutationFn: () => api.dispatchDeliveryNote(deliveryNoteId, vehicleRef.trim(), driverName.trim(), dispatchKey.current()),
    ...after(dispatchKey)
  });

  if (note.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (note.isError || !note.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(note.error, t("trading.error.not_found").text)}</p>
        <Link to="/trading">{t("trading.back").text}</Link>
      </main>
    );
  }

  const n = note.data;
  const isSeller = n.sellerEntityId === scope.entityId;
  const isBuyer = n.buyerEntityId === scope.entityId;
  const failed = [issue, dispatch].find((m) => m.isError);

  return (
    <main className="shell-page">
      <Link to="/trading">{t("trading.back").text}</Link>
      <DocumentHeader
        code={n.docNumber ?? t("trading.order.draft_number").text}
        title={t("trading.note.title").text}
        state={{ look: deliveryChip(n.status), label: t(`trading.note.status.${n.status}`).text }}
        facts={[
          { label: t("trading.column.seller").text, value: <EntityName entityId={n.sellerEntityId} /> },
          { label: t("trading.column.buyer").text, value: <EntityName entityId={n.buyerEntityId} /> },
          { label: t("trading.field.from_location").text, value: n.fromLocationId && <LocationName locationId={n.fromLocationId} /> },
          { label: t("trading.field.vehicle").text, value: n.vehicleRef },
          { label: t("trading.field.driver").text, value: n.driverName },
          { label: t("trading.field.issued_at").text, value: n.issuedAt && formatInstant(n.issuedAt) },
          { label: t("trading.field.dispatched_at").text, value: n.dispatchedAt && formatInstant(n.dispatchedAt) }
        ]}
      />

      {isSeller && n.status === "ISSUED" && <p>{t("trading.note.reserved").text}</p>}
      {n.status === "IN_TRANSIT" && <p>{t("trading.note.in_transit").text}</p>}
      {isSeller && n.status !== "DRAFT" && (
        <p>
          <Link to="/inventory">{t("trading.stock.link").text}</Link>
        </p>
      )}

      {n.drops.map((drop) => (
        <section key={drop.dropId}>
          <h2>
            {t("trading.note.drop", undefined, { seq: drop.seq }).text} <LocationName locationId={drop.shipToLocationId} />
          </h2>
          <table>
            <thead>
              <tr>
                <th>{t("trading.column.item").text}</th>
                <th>{t("trading.column.unit").text}</th>
                <th>{t("trading.column.qty").text}</th>
                <th>{t("trading.column.order").text}</th>
              </tr>
            </thead>
            <tbody>
              {drop.lines.map((line) => (
                <tr key={line.lineId}>
                  <td>
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td>{line.uomCode}</td>
                  <td>{line.dispatchedQty}</td>
                  <td>
                    <Link to={`/trading/orders/${line.orderId}`}>{t("trading.order.open").text}</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {drop.grnId && (
            <p>
              <Link to={`/trading/grns/${drop.grnId}`}>{t("trading.grn.open").text}</Link>
            </p>
          )}
          {isBuyer && canReceive && !drop.grnId && (n.status === "ISSUED" || n.status === "IN_TRANSIT") && (
            <p>
              <Link to={`/trading/grns/new?deliveryNoteId=${n.deliveryNoteId}&dropId=${drop.dropId}`}>
                {t("trading.grn.new").text}
              </Link>
            </p>
          )}
        </section>
      ))}

      <section style={{ display: "flex", flexWrap: "wrap", gap: "var(--space-2)", alignItems: "end", marginTop: "var(--space-3)" }}>
        {isSeller && canIssue && n.status === "DRAFT" && (
          <button type="button" disabled={issue.isPending} onClick={() => issue.mutate()}>
            {t("trading.note.issue").text}
          </button>
        )}
        {isSeller && canDispatch && n.status === "ISSUED" && (
          <>
            <label style={{ display: "grid", gap: "var(--space-half)" }}>
              {t("trading.field.vehicle").text}
              <input value={vehicleRef} maxLength={40} onChange={(event) => setVehicleRef(event.target.value)} />
            </label>
            <label style={{ display: "grid", gap: "var(--space-half)" }}>
              {t("trading.field.driver").text}
              <input value={driverName} maxLength={120} onChange={(event) => setDriverName(event.target.value)} />
            </label>
            <button
              type="button"
              disabled={dispatch.isPending || !vehicleRef.trim() || !driverName.trim()}
              onClick={() => dispatch.mutate()}
            >
              {t("trading.note.dispatch").text}
            </button>
          </>
        )}
      </section>
      {failed && <p role="alert">{errorText(failed.error, t("trading.error.generic").text)}</p>}
    </main>
  );
}
