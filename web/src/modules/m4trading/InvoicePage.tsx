import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { openServerFile } from "../../shell/api/openServerFile";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { useFormatDate } from "../../shell/i18n/formats";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { EntityName, SkuLabel } from "./labels";
import { PaymentEntry } from "./PaymentEntry";
import { useTradingApi } from "./tradingApi";
import { errorText, paymentStateChip } from "./tradingView";

/**
 * One tax invoice (24A section 8, "Invoice", demo scope; M4-08): the seller's invoice built from
 * the buyer's confirmed goods received notes, each line at the tier price with its VAT, and the
 * totals. Both parties read it; it leads to the GRNs and the delivery note it came from. The
 * Print opens the A4 PDF the worker printed (a fresh pre-signed link each time), for the seller and
 * for the buyer alike: the PDF is stored under the seller and the buyer reaches it through this invoice.
 * M4-07: what payments settled, the amount due and the payment state (open, part-paid, settled),
 * the payments against it, and for the seller's accounts the form that records one against it.
 */
export function InvoicePage() {
  const { invoiceId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();

  const invoice = useQuery({ queryKey: ["trading", "invoice", invoiceId], queryFn: () => api.invoice(invoiceId) });
  const firstGrn = invoice.data?.grnIds[0];
  const grn = useQuery({
    queryKey: ["trading", "grn", firstGrn],
    queryFn: () => api.grn(firstGrn!),
    enabled: firstGrn !== undefined,
    retry: false
  });
  const print = useMutation({ mutationFn: () => openServerFile(() => api.invoicePrint(invoiceId)) });
  // The buyer disputes the invoice with a reason; either party holding the permission closes it.
  const scope = useScope();
  const queryClient = useQueryClient();
  const canDispute = useHasPermission("bil.invoice.dispute");
  const canRecordPayment = useHasPermission("bil.payment.record");
  const [disputeReason, setDisputeReason] = useState("");
  const disputeKey = useIdempotencyKey();
  const resolveKey = useIdempotencyKey();
  const dispute = useMutation({
    mutationFn: () => api.disputeInvoice(invoiceId, disputeReason.trim(), disputeKey.current()),
    onSuccess: () => {
      disputeKey.next();
      setDisputeReason("");
      queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        disputeKey.next();
      }
    }
  });
  const resolve = useMutation({
    mutationFn: () => api.resolveInvoiceDispute(invoiceId, resolveKey.current()),
    onSuccess: () => {
      resolveKey.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        resolveKey.next();
      }
    }
  });
  // The tab is opened in the click itself, so a popup blocker lets it through, and is sent to
  // the PDF once the link arrives (openServerFile).
  const openPrint = () => print.mutate();

  if (invoice.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (invoice.isError || !invoice.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(invoice.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      </main>
    );
  }

  const inv = invoice.data;
  const isBuyer = inv.buyerEntityId === scope.entityId;
  const credited = (inv.creditedAmount ?? 0) > 0;
  const paid = (inv.payments ?? []).length > 0;
  const amountDue = inv.amountDue ?? inv.grossAmount;
  const paymentState = inv.paymentState ?? "OPEN";

  return (
    <main className="shell-page">
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <DocumentHeader
        code={inv.docNumber ?? t("trading.order.draft_number").text}
        title={t("trading.invoice.title").text}
        state={
          inv.disputed
            ? { look: "disputed", label: t("trading.invoice.status.DISPUTED").text }
            : { look: "issued", label: t("trading.invoice.status.ISSUED").text }
        }
        facts={[
          { label: t("trading.column.seller").text, value: <EntityName entityId={inv.sellerEntityId} /> },
          { label: t("trading.invoice.seller_vat").text, value: inv.sellerVatNo },
          { label: t("trading.column.buyer").text, value: <EntityName entityId={inv.buyerEntityId} /> },
          { label: t("trading.invoice.buyer_vat").text, value: inv.buyerVatNo },
          {
            label: t("trading.column.payment_state").text,
            value: <StateChip state={paymentStateChip(paymentState)} label={t(`trading.invoice.payment_state.${paymentState}`).text} />
          },
          {
            label: t("trading.account.title").text,
            value: (
              <Link to={isBuyer ? `/trading/accounts/BUYER/${inv.sellerEntityId}` : `/trading/accounts/SELLER/${inv.buyerEntityId}`}>
                {t("trading.accounts.open").text}
              </Link>
            )
          },
          { label: t("trading.invoice.tax_point").text, value: formatDate(inv.taxPointDate) },
          { label: t("trading.invoice.due").text, value: formatDate(inv.dueDate) },
          {
            label: t("trading.grns.title").text,
            value: (
              <>
                {inv.grnIds.map((id, index) => (
                  <Link key={id} to={`/trading/grns/${id}`}>
                    {index === 0 && grn.data?.docNumber ? grn.data.docNumber : t("trading.grn.open").text}
                  </Link>
                ))}
              </>
            )
          },
          {
            label: t("trading.note.title").text,
            value: grn.data?.deliveryNoteId && (
              <Link to={`/trading/delivery-notes/${grn.data.deliveryNoteId}`}>{t("trading.note.open").text}</Link>
            )
          }
        ]}
      >
        <button type="button" disabled={print.isPending} onClick={openPrint}>
          {t("trading.invoice.print").text}
        </button>
      </DocumentHeader>
      {print.isError && <p role="alert">{errorText(print.error, t("trading.error.generic").text)}</p>}

      <table>
        <thead>
          <tr>
            <th>{t("trading.column.item").text}</th>
            <th>{t("trading.column.unit").text}</th>
            <th>{t("trading.column.qty").text}</th>
            <th>{t("trading.column.tier_price").text}</th>
            <th>{t("trading.invoice.vat_rate").text}</th>
            <th>{t("trading.invoice.vat").text}</th>
            <th>{t("trading.column.amount").text}</th>
          </tr>
        </thead>
        <tbody>
          {inv.lines.map((line) => (
            <tr key={line.lineId}>
              <td>
                <SkuLabel skuId={line.skuId} />
              </td>
              <td>{line.uomCode}</td>
              <td>{line.qty}</td>
              <td>
                <MoneyDisplay amount={line.unitPrice} />
              </td>
              <td>{line.taxRatePercent}</td>
              <td>
                <MoneyDisplay amount={line.taxAmount} />
              </td>
              <td>
                <MoneyDisplay amount={line.lineTotal} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <dl className="document-header__facts trading-section">
        <div className="document-header__fact">
          <dt>{t("trading.invoice.net").text}</dt>
          <dd>
            <MoneyDisplay amount={inv.netAmount} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.invoice.vat").text}</dt>
          <dd>
            <MoneyDisplay amount={inv.taxAmount} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.invoice.gross").text}</dt>
          <dd>
            <MoneyDisplay amount={inv.grossAmount} size={credited || paid ? undefined : "total"} />
          </dd>
        </div>
        {credited && (
          <div className="document-header__fact">
            <dt>{t("trading.invoice.credited").text}</dt>
            <dd>
              <MoneyDisplay amount={inv.creditedAmount ?? 0} />
            </dd>
          </div>
        )}
        {paid && (
          <div className="document-header__fact">
            <dt>{t("trading.invoice.settled").text}</dt>
            <dd>
              <MoneyDisplay amount={inv.settledAmount ?? 0} />
            </dd>
          </div>
        )}
        {(credited || paid) && (
          <div className="document-header__fact">
            <dt>{t("trading.invoice.amount_due").text}</dt>
            <dd>
              <MoneyDisplay amount={amountDue} size="total" />
            </dd>
          </div>
        )}
      </dl>

      {paid && (
        <section className="trading-section">
          <h2>{t("trading.invoice.payments").text}</h2>
          <ul>
            {(inv.payments ?? []).map((payment) => (
              <li key={payment.receiptId}>
                <Link to={`/trading/payments/${payment.receiptId}`}>{payment.docNumber ?? t("trading.payment.title").text}</Link>{" "}
                {payment.receivedOn && formatDate(payment.receivedOn)} <MoneyDisplay amount={payment.amount} />{" "}
                {t(`trading.payment.status.${payment.status}`).text}
              </li>
            ))}
          </ul>
        </section>
      )}

      {canRecordPayment && !isBuyer && amountDue > 0 && (
        <PaymentEntry
          key={amountDue}
          buyerEntityId={inv.buyerEntityId}
          invoiceId={inv.invoiceId}
          amountDue={amountDue}
        />
      )}

      {(inv.creditNotes ?? []).length > 0 && (
        <section className="trading-section">
          <h2>{t("trading.invoice.credit_notes").text}</h2>
          <ul>
            {(inv.creditNotes ?? []).map((note) => (
              <li key={note.creditNoteId}>
                <Link to={`/trading/credit-notes/${note.creditNoteId}`}>{note.docNumber ?? t("trading.creditnote.title").text}</Link>{" "}
                <MoneyDisplay amount={note.grossAmount} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {canDispute && isBuyer && !inv.disputed && (
        <section className="trading-section">
          <label>
            {t("trading.invoice.dispute_reason").text}
            <input type="text" maxLength={500} value={disputeReason} onChange={(event) => setDisputeReason(event.target.value)} />
          </label>
          <button type="button" disabled={dispute.isPending || disputeReason.trim() === ""} onClick={() => dispute.mutate()}>
            {t("trading.invoice.dispute").text}
          </button>
        </section>
      )}
      {canDispute && inv.disputed && (
        <section className="trading-section">
          <button type="button" disabled={resolve.isPending} onClick={() => resolve.mutate()}>
            {t("trading.invoice.resolve").text}
          </button>
        </section>
      )}
      {(dispute.isError || resolve.isError) && (
        <p role="alert">{errorText(dispute.error ?? resolve.error, t("trading.error.generic").text)}</p>
      )}
    </main>
  );
}
