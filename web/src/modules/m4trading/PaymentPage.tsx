import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { openServerFile } from "../../shell/api/openServerFile";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { useScope } from "../../shell/scope/useScope";
import { EntityName } from "./labels";
import { useTradingApi } from "./tradingApi";
import { errorText } from "./tradingView";

/**
 * One payment receipt (24A section 8, "Receipt book and matching"; M4-07): what the seller
 * received, how, what it settled of each invoice and what stays on account. Both parties read it;
 * the seller's accounts record a cheque's outcome, and a bounced cheque shows the reversal that
 * reopened the invoices. Print opens the A4 PDF the worker printed (as for the invoice); the seller's
 * accounts apply what the receipt holds on account to the buyer's open invoices, oldest first.
 */
export function PaymentPage() {
  const { receiptId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const canRecord = useHasPermission("bil.payment.record");
  const [reason, setReason] = useState("");
  const key = useIdempotencyKey();

  const applyKey = useIdempotencyKey();
  const receipt = useQuery({ queryKey: ["trading", "payment", receiptId], queryFn: () => api.payment(receiptId) });
  // ApplyReceipt (24A section 6.3): what the receipt holds on account settles the open invoices, oldest first.
  const apply = useMutation({
    mutationFn: () => api.applyPayment(receiptId, {}, applyKey.current()),
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
  const print = useMutation({ mutationFn: () => openServerFile(() => api.paymentPrint(receiptId)) });
  // The tab is opened in the click itself, so a popup blocker lets it through (as InvoicePage).
  const openPrint = () => print.mutate();
  const outcome = useMutation({
    mutationFn: (value: "CLEARED" | "BOUNCED") => api.chequeOutcome(receiptId, value, reason, key.current()),
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

  if (receipt.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (receipt.isError || !receipt.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(receipt.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
          {t("trading.back").text}
        </Link>
      </main>
    );
  }

  const prc = receipt.data;
  const isSeller = prc.sellerEntityId === scope.entityId;
  const cheque = prc.cheque;
  const chequeState = cheque?.outcome ? t(`trading.payment.cheque.${cheque.outcome}`).text : t("trading.payment.cheque.pending").text;

  return (
    <main className="shell-page">
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <DocumentHeader
        code={prc.docNumber ?? ""}
        title={t("trading.payment.title").text}
        state={
          prc.status === "RECORDED"
            ? { look: "issued", label: t("trading.payment.status.RECORDED").text }
            : { look: "void", label: t(`trading.payment.status.${prc.status}`).text }
        }
        facts={[
          { label: t("trading.column.seller").text, value: <EntityName entityId={prc.sellerEntityId} /> },
          { label: t("trading.column.buyer").text, value: <EntityName entityId={prc.buyerEntityId} /> },
          { label: t("trading.payment.method").text, value: t(`trading.payment.method.${prc.method}`).text },
          { label: t("trading.payment.received_on").text, value: formatDate(prc.receivedOn) },
          { label: t("trading.payment.amount").text, value: <MoneyDisplay amount={prc.amount} size="total" /> },
          { label: t("trading.payment.unapplied").text, value: <MoneyDisplay amount={prc.unappliedAmount} /> },
          { label: t("trading.payment.reference").text, value: prc.reference ?? "" },
          {
            label: t("trading.payment.reversal_of").text,
            value: prc.reversalOf && <Link to={`/trading/payments/${prc.reversalOf}`}>{t("trading.payment.open").text}</Link>
          },
          {
            label: t("trading.payment.reversed_by").text,
            value: prc.reversedBy && <Link to={`/trading/payments/${prc.reversedBy}`}>{t("trading.payment.open").text}</Link>
          }
        ]}
      />

      <section className="trading-filter-bar">
        {prc.status !== "REVERSAL" && (
          <button type="button" disabled={print.isPending} onClick={openPrint}>
            {t("trading.payment.print").text}
          </button>
        )}
        {canRecord && isSeller && prc.status === "RECORDED" && prc.unappliedAmount > 0 && (
          <button type="button" disabled={apply.isPending} onClick={() => apply.mutate()}>
            {t("trading.payment.apply").text}
          </button>
        )}
      </section>
      {print.isError && <p role="alert">{errorText(print.error, t("trading.error.generic").text)}</p>}
      {apply.isError && <p role="alert">{errorText(apply.error, t("trading.error.generic").text)}</p>}
      {apply.isSuccess && <p role="status">{t("trading.payment.applied").text}</p>}

      {cheque && (
        <section className="trading-section">
          <h2>{t("trading.payment.cheque").text}</h2>
          <dl className="document-header__facts">
            <div className="document-header__fact">
              <dt>{t("trading.payment.bank").text}</dt>
              <dd>{cheque.bank}</dd>
            </div>
            <div className="document-header__fact">
              <dt>{t("trading.payment.cheque_no").text}</dt>
              <dd>{cheque.chequeNo}</dd>
            </div>
            <div className="document-header__fact">
              <dt>{t("trading.payment.cheque_dated").text}</dt>
              <dd>{formatDate(cheque.dated)}</dd>
            </div>
            <div className="document-header__fact">
              <dt>{t("trading.column.status").text}</dt>
              <dd>{chequeState}</dd>
            </div>
          </dl>
          {canRecord && isSeller && !cheque.outcome && prc.status === "RECORDED" && (
            <div className="trading-filter-bar">
              <button type="button" disabled={outcome.isPending} onClick={() => outcome.mutate("CLEARED")}>
                {t("trading.payment.cleared").text}
              </button>
              <label className="trading-form-field">
                {t("trading.payment.bounce_reason").text}
                <input type="text" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
              </label>
              <button type="button" disabled={outcome.isPending} onClick={() => outcome.mutate("BOUNCED")}>
                {t("trading.payment.bounced").text}
              </button>
            </div>
          )}
          {outcome.isError && <p role="alert">{errorText(outcome.error, t("trading.error.generic").text)}</p>}
        </section>
      )}

      <section className="trading-section">
        <h2>{t("trading.payment.allocations").text}</h2>
        {prc.allocations.length === 0 ? (
          <p>{t("trading.payment.none_settled").text}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t("trading.invoice.title").text}</th>
                <th>{t("trading.column.amount").text}</th>
              </tr>
            </thead>
            <tbody>
              {prc.allocations.map((allocation, index) => (
                <tr key={`${allocation.invoiceId}-${index}`}>
                  <td>
                    <Link to={`/trading/invoices/${allocation.invoiceId}`}>{allocation.invoiceNumber ?? t("trading.invoice.title").text}</Link>
                  </td>
                  <td>
                    <MoneyDisplay amount={allocation.amount} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
