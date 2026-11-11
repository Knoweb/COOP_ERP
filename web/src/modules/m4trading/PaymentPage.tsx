import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { openServerFile } from "../../shell/api/openServerFile";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { useScope } from "../../shell/scope/useScope";
import { EntityName } from "./labels";
import { useTradingApi } from "./tradingApi";
import { errorText } from "./tradingView";
import "./trading.css";

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
    return <main className="shell-page payment-page"><p className="trading-state-box">{t("trading.loading").text}</p></main>;
  }
  if (receipt.isError || !receipt.data) {
    return (
      <main className="shell-page payment-page">
        <div className="payment-back-bar">
          <Link className="back-pill-btn" to="/trading">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="m12 19-7-7 7-7" />
              <path d="M19 12H5" />
            </svg>
            {t("trading.back").text}
          </Link>
        </div>
        <div className="trading-feedback--error" role="alert">
          <p className="trading-feedback__text">{errorText(receipt.error, t("trading.error.not_found").text)}</p>
        </div>
      </main>
    );
  }

  const prc = receipt.data;
  const isSeller = prc.sellerEntityId === scope.entityId;
  const cheque = prc.cheque;
  const chequeState = cheque?.outcome ? t(`trading.payment.cheque.${cheque.outcome}`).text : t("trading.payment.cheque.pending").text;
  const chequeLook = cheque?.outcome === "BOUNCED" ? "alert" : cheque?.outcome === "CLEARED" ? "issued" : "draft";
  const showApply = canRecord && isSeller && prc.status === "RECORDED" && prc.unappliedAmount > 0;
  const showPrint = prc.status !== "REVERSAL";

  return (
    <main className="shell-page payment-page">
      <div className="payment-back-bar">
        <Link className="back-pill-btn" to="/trading">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("trading.back").text}
        </Link>
      </div>

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
            value: prc.reversalOf && (
              <Link className="trading-doc-link" to={`/trading/payments/${prc.reversalOf}`}>
                {t("trading.payment.open").text}
              </Link>
            )
          },
          {
            label: t("trading.payment.reversed_by").text,
            value: prc.reversedBy && (
              <Link className="trading-doc-link" to={`/trading/payments/${prc.reversedBy}`}>
                {t("trading.payment.open").text}
              </Link>
            )
          }
        ]}
      >
        {(showPrint || showApply) && (
          <div className="payment-header-actions">
            {showPrint && (
              <button
                type="button"
                className="payment-btn payment-btn--secondary"
                disabled={print.isPending}
                onClick={openPrint}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <polyline points="6 9 6 2 18 2 18 9" />
                  <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
                  <rect x="6" y="14" width="12" height="8" />
                </svg>
                <span>{t("trading.payment.print").text}</span>
              </button>
            )}
            {showApply && (
              <button
                type="button"
                className="modern-btn modern-btn--primary payment-btn"
                disabled={apply.isPending}
                onClick={() => apply.mutate()}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                <span>{t("trading.payment.apply").text}</span>
              </button>
            )}
          </div>
        )}
      </DocumentHeader>

      {print.isError && (
        <div className="trading-feedback--error payment-feedback" role="alert">
          <p className="trading-feedback__text">{errorText(print.error, t("trading.error.generic").text)}</p>
        </div>
      )}
      {apply.isError && (
        <div className="trading-feedback--error payment-feedback" role="alert">
          <p className="trading-feedback__text">{errorText(apply.error, t("trading.error.generic").text)}</p>
        </div>
      )}
      {apply.isSuccess && (
        <p className="payment-feedback payment-feedback--success" role="status">
          {t("trading.payment.applied").text}
        </p>
      )}

      {cheque && (
        <section className="trading-section payment-section">
          <div className="payment-section__header">
            <div className="payment-section__title-group">
              <span className="payment-section__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <rect x="2" y="5" width="20" height="14" rx="2" />
                  <line x1="2" y1="10" x2="22" y2="10" />
                </svg>
              </span>
              <h2>{t("trading.payment.cheque").text}</h2>
            </div>
          </div>
          <dl className="document-header__facts payment-cheque-facts">
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
              <dd>
                <StateChip state={chequeLook} label={chequeState} />
              </dd>
            </div>
          </dl>
          {canRecord && isSeller && !cheque.outcome && prc.status === "RECORDED" && (
            <div className="payment-outcome-panel">
              <label className="payment-outcome-field">
                <span>{t("trading.payment.bounce_reason").text}</span>
                <input type="text" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
              </label>
              <div className="payment-outcome-actions">
                <button
                  type="button"
                  className="modern-btn modern-btn--primary payment-btn"
                  disabled={outcome.isPending}
                  onClick={() => outcome.mutate("CLEARED")}
                >
                  {t("trading.payment.cleared").text}
                </button>
                <button
                  type="button"
                  className="payment-btn payment-btn--danger"
                  disabled={outcome.isPending}
                  onClick={() => outcome.mutate("BOUNCED")}
                >
                  {t("trading.payment.bounced").text}
                </button>
              </div>
            </div>
          )}
          {outcome.isError && (
            <div className="trading-feedback--error payment-feedback" role="alert">
              <p className="trading-feedback__text">{errorText(outcome.error, t("trading.error.generic").text)}</p>
            </div>
          )}
        </section>
      )}

      <section className="trading-section payment-section">
        <div className="payment-section__header">
          <div className="payment-section__title-group">
            <span className="payment-section__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <path d="M14 2v6h6" />
                <path d="M8 13h8" />
                <path d="M8 17h5" />
              </svg>
            </span>
            <h2>{t("trading.payment.allocations").text}</h2>
          </div>
          <span className={`trading-count-pill ${prc.allocations.length > 0 ? "trading-count-pill--active" : "trading-count-pill--zero"}`}>
            {prc.allocations.length}
          </span>
        </div>
        {prc.allocations.length === 0 ? (
          <div className="trading-empty-box">
            <p className="trading-empty-box__text">{t("trading.payment.none_settled").text}</p>
          </div>
        ) : (
          <div className="modern-table-card trading-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table trading-table payment-allocations-table">
                <thead>
                  <tr>
                    <th scope="col">{t("trading.invoice.title").text}</th>
                    <th scope="col" className="trading-cell-numeric">{t("trading.column.amount").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {prc.allocations.map((allocation, index) => (
                    <tr key={`${allocation.invoiceId}-${index}`}>
                      <td>
                        <Link className="trading-doc-link" to={`/trading/invoices/${allocation.invoiceId}`}>
                          {allocation.invoiceNumber ?? t("trading.invoice.title").text}
                        </Link>
                      </td>
                      <td className="trading-cell-numeric">
                        <MoneyDisplay amount={allocation.amount} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}
