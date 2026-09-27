import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { EntityName, LocationName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { businessToday, canDeliver, errorText, orderChip } from "./tradingView";

const CANCEL_REASONS = ["NOT_NEEDED", "WRONG_ITEMS", "OTHER"];
const REJECT_REASONS = ["NO_STOCK", "NOT_SUPPLIED", "OTHER"];

/**
 * One order as both parties see it (doc 24 section 5.2; 24A section 8, demo scope). The buyer
 * submits a draft or cancels it; the seller, at its order desk, sees what it can allocate of each
 * item and accepts with a committed delivery date (the allocation is computed by the server,
 * min(open, available)) or rejects with a reason. An accepted order leads to its delivery note.
 */
export function OrderPage() {
  const { orderId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const canSubmit = useHasPermission("ord.order.submit");
  const canAccept = useHasPermission("ord.order.accept");
  const canDraftNote = useHasPermission("del.note.draft");
  const submitKey = useIdempotencyKey();
  const cancelKey = useIdempotencyKey();
  const acceptKey = useIdempotencyKey();
  const rejectKey = useIdempotencyKey();
  const [asking, setAsking] = useState<"cancel" | "reject" | null>(null);
  const [eta, setEta] = useState(businessToday());

  const order = useQuery({ queryKey: ["trading", "order", orderId], queryFn: () => api.order(orderId) });
  const isSeller = order.data !== undefined && order.data.sellerEntityId === scope.entityId;
  const deciding = isSeller && canAccept && order.data?.status === "SUBMITTED";
  const skuIds = order.data?.lines.map((line) => line.skuId) ?? [];
  const availability = useQuery({
    queryKey: ["trading", "availability", orderId, skuIds.join(",")],
    queryFn: () => api.sellerAvailability(order.data!.sellerEntityId, skuIds),
    enabled: deciding && skuIds.length > 0
  });

  type Reason = { code: string; text: string | null };
  const run = (key: { current: () => string; next: () => void }, call: (k: string, reason: Reason) => Promise<unknown>) => ({
    mutationFn: (reason: Reason = { code: "", text: null }) => call(key.current(), reason),
    onSuccess: () => {
      key.next();
      setAsking(null);
      queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (error: unknown) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const submit = useMutation(run(submitKey, (k) => api.submitOrder(orderId, k)));
  const accept = useMutation(run(acceptKey, (k) => api.acceptOrder(orderId, eta, [], k)));
  const cancel = useMutation(run(cancelKey, (k, reason) => api.cancelOrder(orderId, reason.code, reason.text, k)));
  const reject = useMutation(run(rejectKey, (k, reason) => api.rejectOrder(orderId, reason.code, reason.text, k)));

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
  const isBuyer = o.buyerEntityId === scope.entityId;
  const accepted = o.lines.some((line) => line.allocatedQty !== undefined && line.allocatedQty !== null);
  const failed = [submit, accept, cancel, reject].find((m) => m.isError);

  return (
    <main className="shell-page">
      <Link to="/trading">{t("trading.back").text}</Link>
      <DocumentHeader
        code={o.docNumber ?? t("trading.order.draft_number").text}
        title={t("trading.order.title").text}
        state={{ look: orderChip(o.status), label: t(`trading.order.status.${o.status}`).text }}
        facts={[
          { label: t("trading.column.buyer").text, value: <EntityName entityId={o.buyerEntityId} /> },
          { label: t("trading.column.seller").text, value: <EntityName entityId={o.sellerEntityId} /> },
          { label: t("trading.field.deliver_to").text, value: o.deliverToLocationId && <LocationName locationId={o.deliverToLocationId} /> },
          { label: t("trading.field.committed_eta").text, value: o.committedEta && formatDate(o.committedEta) },
          { label: t("trading.column.amount").text, value: <MoneyDisplay amount={o.netAmount} /> },
          { label: t("trading.field.reject_reason").text, value: o.rejectReasonCode }
        ]}
      />

      <table>
        <thead>
          <tr>
            <th>{t("trading.column.item").text}</th>
            <th>{t("trading.column.unit").text}</th>
            <th>{t("trading.column.qty").text}</th>
            <th>{t("trading.column.indicative_price").text}</th>
            {deciding && <th>{t("trading.column.available").text}</th>}
            {accepted && <th>{t("trading.column.allocated").text}</th>}
            {accepted && <th>{t("trading.column.tier_price").text}</th>}
            {accepted && <th>{t("trading.column.delivered").text}</th>}
          </tr>
        </thead>
        <tbody>
          {o.lines.map((line) => (
            <tr key={line.lineId}>
              <td>
                <SkuLabel skuId={line.skuId} />
              </td>
              <td>{line.uomCode}</td>
              <td>{line.requestedQty}</td>
              <td>
                <MoneyDisplay amount={line.indicativePrice} />
              </td>
              {deciding && <td>{availability.data?.[line.skuId] ?? ""}</td>}
              {accepted && <td>{line.allocatedQty}</td>}
              {accepted && (
                <td>
                  <MoneyDisplay amount={line.tierPrice} />
                </td>
              )}
              {accepted && <td>{line.fulfilledQty}</td>}
            </tr>
          ))}
        </tbody>
      </table>

      <section style={{ display: "flex", flexWrap: "wrap", gap: "var(--space-2)", alignItems: "end", marginTop: "var(--space-3)" }}>
        {isBuyer && canSubmit && o.status === "DRAFT" && (
          <button type="button" disabled={submit.isPending} onClick={() => submit.mutate()}>
            {t("trading.order.submit").text}
          </button>
        )}
        {isBuyer && canSubmit && (o.status === "DRAFT" || o.status === "SUBMITTED") && (
          <button type="button" onClick={() => setAsking("cancel")}>
            {t("trading.order.cancel").text}
          </button>
        )}
        {deciding && (
          <>
            <label style={{ display: "grid", gap: "var(--space-half)" }}>
              {t("trading.field.committed_eta").text}
              <input type="date" min={businessToday()} value={eta} onChange={(event) => setEta(event.target.value)} />
            </label>
            <button type="button" disabled={accept.isPending || !eta} onClick={() => accept.mutate()}>
              {t("trading.order.accept").text}
            </button>
            <button type="button" onClick={() => setAsking("reject")}>
              {t("trading.order.reject").text}
            </button>
          </>
        )}
        {isSeller && canDraftNote && canDeliver(o) && (
          <Link to={`/trading/delivery-notes/new?orderId=${o.orderId}`}>{t("trading.note.new").text}</Link>
        )}
      </section>

      {asking && (
        <ReasonCapture
          title={t(asking === "cancel" ? "trading.order.cancel.question" : "trading.order.reject.question").text}
          codes={(asking === "cancel" ? CANCEL_REASONS : REJECT_REASONS).map((code) => ({
            code,
            label: t(`trading.reason.${code}`).text
          }))}
          pending={cancel.isPending || reject.isPending}
          onCancel={() => setAsking(null)}
          onConfirm={(code, text) => (asking === "cancel" ? cancel : reject).mutate({ code, text })}
        />
      )}
      {failed && <p role="alert">{errorText(failed.error, t("trading.error.generic").text)}</p>}
    </main>
  );
}
