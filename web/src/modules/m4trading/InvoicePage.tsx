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
  // The seller's accounts apply what a credit note of the same buyer still holds to this invoice
  // (CR-24A-3 item 2): as much as fits, the server works it out.
  const canIssueCredit = useHasPermission("bil.creditnote.issue");
  const applyKey = useIdempotencyKey();
  const applyCredit = useMutation({
    mutationFn: (creditNoteId: string) => api.applyCreditNote(creditNoteId, invoiceId, applyKey.current()),
    onSuccess: () => {
      applyKey.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        applyKey.next();
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
        <Link className="back-link back-pill-btn" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      </main>
    );
  }

  const inv = invoice.data;
  const isBuyer = inv.buyerEntityId === scope.entityId;
  const credited = (inv.creditedAmount ?? 0) > 0;
  const debited = (inv.debitedAmount ?? 0) > 0;
  const paid = (inv.payments ?? []).length > 0;
  const amountDue = inv.amountDue ?? inv.grossAmount;
  const paymentState = inv.paymentState ?? "OPEN";

  return (
    <main className="shell-page">
      <Link className="back-link back-pill-btn" to="/trading">
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
              <Link
                className="trading-doc-link invoice-doc-link"
                to={isBuyer ? `/trading/accounts/BUYER/${inv.sellerEntityId}` : `/trading/accounts/SELLER/${inv.buyerEntityId}`}
              >
                <svg
                  className="invoice-doc-link__icon"
                  viewBox="0 0 24 24"
                  width="14"
                  height="14"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <rect x="2" y="5" width="20" height="14" rx="2" />
                  <line x1="2" y1="10" x2="22" y2="10" />
                </svg>
                <span>{t("trading.accounts.open").text}</span>
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
                  <Link
                    key={id}
                    className="trading-doc-link invoice-doc-link"
                    to={`/trading/grns/${id}`}
                  >
                    <svg
                      className="invoice-doc-link__icon"
                      viewBox="0 0 24 24"
                      width="14"
                      height="14"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                    >
                      <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
                    </svg>
                    <span>{index === 0 && grn.data?.docNumber ? grn.data.docNumber : t("trading.grn.open").text}</span>
                  </Link>
                ))}
              </>
            )
          },
          {
            label: t("trading.note.title").text,
            value: grn.data?.deliveryNoteId ? (
              <Link
                className="trading-doc-link invoice-doc-link"
                to={`/trading/delivery-notes/${grn.data.deliveryNoteId}`}
              >
                <svg
                  className="invoice-doc-link__icon"
                  viewBox="0 0 24 24"
                  width="14"
                  height="14"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                  <line x1="16" y1="13" x2="8" y2="13" />
                  <line x1="16" y1="17" x2="8" y2="17" />
                  <polyline points="10 9 9 9 8 9" />
                </svg>
                <span>{t("trading.note.open").text}</span>
              </Link>
            ) : undefined
          }
        ]}
      >
        <div className="invoice-header-actions">
          <button
            type="button"
            className="trading-print-btn invoice-print-btn"
            disabled={print.isPending}
            onClick={openPrint}
          >
            <svg
              className="invoice-print-btn__icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <polyline points="6 9 6 2 18 2 18 9" />
              <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
              <rect x="6" y="14" width="12" height="8" />
            </svg>
            <span>{t("trading.invoice.print").text}</span>
          </button>
        </div>
      </DocumentHeader>
      {print.isError && <p role="alert">{errorText(print.error, t("trading.error.generic").text)}</p>}

      <section className="modern-table-card invoice-lines-card">
        <div className="modern-table-scroll">
          <table className="modern-table invoice-table">
            <thead>
              <tr>
                <th>{t("trading.column.item").text}</th>
                <th>{t("trading.column.unit").text}</th>
                <th className="numeric-cell">{t("trading.column.qty").text}</th>
                <th className="numeric-cell">{t("trading.column.tier_price").text}</th>
                <th className="numeric-cell">{t("trading.invoice.vat_rate").text}</th>
                <th className="numeric-cell">{t("trading.invoice.vat").text}</th>
                <th className="numeric-cell">{t("trading.column.amount").text}</th>
              </tr>
            </thead>
            <tbody>
              {inv.lines.map((line) => (
                <tr key={line.lineId}>
                  <td className="font-semibold">
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td>{line.uomCode}</td>
                  <td className="numeric-cell font-tabular">{line.qty}</td>
                  <td className="numeric-cell font-tabular">
                    <MoneyDisplay amount={line.unitPrice} />
                  </td>
                  <td className="numeric-cell font-tabular">{line.taxRatePercent}</td>
                  <td className="numeric-cell font-tabular">
                    <MoneyDisplay amount={line.taxAmount} />
                  </td>
                  <td className="numeric-cell font-tabular font-semibold">
                    <MoneyDisplay amount={line.lineTotal} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="modern-table-card invoice-totals-card">
        <dl className="document-header__facts invoice-totals-grid trading-section">
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
          {debited && (
            <div className="document-header__fact">
              <dt>{t("trading.invoice.debited").text}</dt>
              <dd>
                <MoneyDisplay amount={inv.debitedAmount ?? 0} />
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
          {(credited || paid || debited) && (
            <div className="document-header__fact">
              <dt>{t("trading.invoice.amount_due").text}</dt>
              <dd>
                <MoneyDisplay amount={amountDue} size="total" />
              </dd>
            </div>
          )}
        </dl>
      </section>

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
                {(note.unappliedAmount ?? 0) > 0 && (
                  <>
                    {" "}
                    {t("trading.creditnote.unapplied").text} <MoneyDisplay amount={note.unappliedAmount ?? 0} />
                  </>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {canIssueCredit && !isBuyer && amountDue > 0 && (inv.availableCredits ?? []).length > 0 && (
        <section className="trading-section">
          <h2>{t("trading.invoice.available_credits").text}</h2>
          <ul>
            {(inv.availableCredits ?? []).map((note) => (
              <li key={note.creditNoteId}>
                <Link to={`/trading/credit-notes/${note.creditNoteId}`}>{note.docNumber ?? t("trading.creditnote.title").text}</Link>{" "}
                {t("trading.creditnote.unapplied").text} <MoneyDisplay amount={note.unappliedAmount ?? 0} />{" "}
                <button type="button" disabled={applyCredit.isPending} onClick={() => applyCredit.mutate(note.creditNoteId)}>
                  {t("trading.invoice.apply_credit").text}
                </button>
              </li>
            ))}
          </ul>
          {applyCredit.isError && <p role="alert">{errorText(applyCredit.error, t("trading.error.generic").text)}</p>}
        </section>
      )}

      {(inv.debitNotes ?? []).length > 0 && (
        <section className="trading-section">
          <h2>{t("trading.invoice.debit_notes").text}</h2>
          <ul>
            {(inv.debitNotes ?? []).map((note) => (
              <li key={note.debitNoteId}>
                <Link to={`/trading/debit-notes/${note.debitNoteId}`}>{note.docNumber ?? t("trading.debitnote.title").text}</Link>{" "}
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
