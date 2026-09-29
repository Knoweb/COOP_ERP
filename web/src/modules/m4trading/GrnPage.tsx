import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import "./trading.css";
import { useScope } from "../../shell/scope/useScope";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { EntityName, LocationName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { errorText, grnChip, isSyntheticBatchNo } from "./tradingView";

/**
 * One goods received note (24A section 6 ConfirmGrn; section 8, "Receive", demo scope). The
 * receiver confirms the counted draft: it takes its number, M2 registers the batches, ownership
 * passes (AGENTS.md idea 2) and M5 moves the goods into the receiver's stock; a short line raises
 * a discrepancy. Once confirmed the card shows the stock movements M5 made and leads to the stock
 * position.
 */
export function GrnPage() {
  const { grnId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const api = useTradingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const canConfirm = useHasPermission("shop.grn.confirm");
  const canInvoice = useHasPermission("bil.invoice.issue");
  // M4-06: the receiver claims against the seller for goods found damaged or expired later.
  const canClaim = useHasPermission("del.claim.raise");
  // The stock a GRN moved is M5's, read with the receiving permission (inv.stock.receive); a
  // buyer who may not receive is not shown it, rather than being refused (403) on every visit.
  const canSeeStock = useHasPermission("inv.stock.receive");
  const navigate = useNavigate();
  const invoiceKey = useIdempotencyKey();
  const key = useIdempotencyKey();

  const grn = useQuery({ queryKey: ["trading", "grn", grnId], queryFn: () => api.grn(grnId) });
  const confirmed = grn.data?.status === "CONFIRMED";
  const receiving = grn.data?.receiverEntityId === scope.entityId;
  // The seller cannot read the buyer's warehouse (CR-24A-2): it names it from the order the
  // GRN's drop delivered, whose delivery point the order kept (V0004).
  const note = useQuery({
    queryKey: ["trading", "delivery-note", grn.data?.deliveryNoteId],
    queryFn: () => api.deliveryNote(grn.data!.deliveryNoteId!),
    enabled: grn.data !== undefined && !receiving && !!grn.data.deliveryNoteId,
    retry: false
  });
  const receipt = useQuery({
    queryKey: ["trading", "receipt", grnId],
    queryFn: () => api.receipt(grnId),
    enabled: confirmed && receiving && canSeeStock,
    retry: false,
    // M5 applies grn.confirmed.v1 after the commit: ask again until the movements are there.
    refetchInterval: (query) => (query.state.status === "success" && query.state.data?.length === 0 ? 2000 : false)
  });

  const confirm = useMutation({
    mutationFn: () => api.confirmGrn(grnId, key.current()),
    onSuccess: () => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  // The seller's accounts invoice the confirmed GRN (M4-08); one GRN per invoice for the demo.
  const invoice = useMutation({
    mutationFn: () => api.issueInvoice([grnId], invoiceKey.current()),
    onSuccess: (issued) => {
      invoiceKey.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
      navigate(`/trading/invoices/${issued.invoiceId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        invoiceKey.next();
      }
    }
  });

  if (grn.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (grn.isError || !grn.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(grn.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      </main>
    );
  }

  const g = grn.data;
  const isReceiver = g.receiverEntityId === scope.entityId;
  const dropOrderId = note.data?.drops.find((drop) => drop.dropId === g.dropId)?.orderIds[0] ?? null;

  return (
    <main className="shell-page">
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <DocumentHeader
        code={g.docNumber ?? t("trading.order.draft_number").text}
        title={t("trading.grn.title").text}
        state={{ look: grnChip(g.status), label: t(`trading.grn.status.${g.status}`).text }}
        facts={[
          { label: t("trading.column.seller").text, value: g.sellerEntityId && <EntityName entityId={g.sellerEntityId} /> },
          {
            label: t("trading.field.receive_at").text,
            value: <LocationName locationId={g.receiverLocationId} own={isReceiver} orderId={dropOrderId} />
          },
          {
            label: t("trading.note.title").text,
            value: g.deliveryNoteId && <Link to={`/trading/delivery-notes/${g.deliveryNoteId}`}>{t("trading.note.open").text}</Link>
          },
          { label: t("trading.field.received_on").text, value: g.receivedOn && formatDate(g.receivedOn) },
          { label: t("trading.field.confirmed_at").text, value: g.confirmedAt && formatInstant(g.confirmedAt) },
          {
            label: t("trading.field.discrepancy").text,
            value: g.discrepancyId && (
              <Link to={`/trading/discrepancies/${g.discrepancyId}`}>{t("trading.grn.discrepancy_raised").text}</Link>
            )
          }
        ]}
      />

      <table>
        <thead>
          <tr>
            <th>{t("trading.column.item").text}</th>
            <th>{t("trading.column.expected").text}</th>
            <th>{t("trading.column.received").text}</th>
            <th>{t("trading.column.damaged").text}</th>
            <th>{t("trading.column.batch").text}</th>
            <th>{t("trading.column.expiry").text}</th>
            <th>{t("trading.column.mrp").text}</th>
            <th>{t("trading.column.unit_cost").text}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {g.lines.map((line) => (
            <tr key={line.lineId}>
              <td>
                <SkuLabel skuId={line.skuId} />
              </td>
              <td>{line.expectedQty}</td>
              <td>{line.receivedQty}</td>
              <td>{line.damagedQty}</td>
              <td>{isSyntheticBatchNo(line.batchNo) ? t("trading.field.batch_not_tracked").text : line.batchNo}</td>
              <td>{line.expiryDate && formatDate(line.expiryDate)}</td>
              <td>{line.printedMrp !== undefined && <MoneyDisplay amount={line.printedMrp} />}</td>
              <td>{line.unitCost !== undefined && <MoneyDisplay amount={line.unitCost} />}</td>
              <td>{line.expectedQty !== undefined && line.receivedQty < line.expectedQty && <strong>{t("trading.grn.short").text}</strong>}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {isReceiver && canConfirm && g.status === "DRAFT" && (
        <section className="trading-section">
          <p>{t("trading.grn.confirm.explain").text}</p>
          <button type="button" disabled={confirm.isPending} onClick={() => confirm.mutate()}>
            {t("trading.grn.confirm").text}
          </button>
        </section>
      )}
      {confirm.isError && <p role="alert">{errorText(confirm.error, t("trading.error.generic").text)}</p>}

      {confirmed && !isReceiver && canInvoice && (
        <section className="trading-section">
          <button type="button" disabled={invoice.isPending} onClick={() => invoice.mutate()}>
            {t("trading.invoice.issue").text}
          </button>
          {invoice.isError && <p role="alert">{errorText(invoice.error, t("trading.error.generic").text)}</p>}
        </section>
      )}

      {confirmed && isReceiver && canClaim && g.sellerEntityId && (
        <section className="trading-section">
          <Link className="action-link" to={`/trading/claims/new?grnId=${g.grnId}`}>
            <span>{t("trading.claim.new").text}</span>
          </Link>
        </section>
      )}

      {confirmed && isReceiver && canSeeStock && (
        <section className="trading-section">
          <h2>{t("trading.grn.stock_moved").text}</h2>
          {receipt.data && receipt.data.length === 0 && <p>{t("trading.grn.stock_pending").text}</p>}
          {receipt.data && receipt.data.length > 0 && (
            <ul>
              {receipt.data.map((movement) => (
                <li key={movement.movementId}>
                  <SkuLabel skuId={movement.skuId} /> {t(`trading.condition.${movement.condition}`).text} +{movement.qtyDelta}
                </li>
              ))}
            </ul>
          )}
          <Link to="/inventory">{t("trading.stock.link").text}</Link>
        </section>
      )}
    </main>
  );
}
