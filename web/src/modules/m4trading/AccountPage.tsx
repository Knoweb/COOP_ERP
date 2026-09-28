import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { ExposurePanel } from "./ExposurePanel";
import { EntityName } from "./labels";
import { PaymentEntry } from "./PaymentEntry";
import { useTradingApi, type Side } from "./tradingApi";
import { errorText, paymentStateChip, receiptChip } from "./tradingView";

/**
 * The account of one trading relationship (24A section 8, "Statement / Exposure", demo scope):
 * the exposure and its credit limit, the invoices with what is paid and due, and the payments,
 * read by both parties. The seller's accounts record a payment here, which settles the buyer's
 * open invoices oldest first. `role` is the caller's side: SELLER reads its buyer's account.
 */
export function AccountPage() {
  const { role: roleParam = "SELLER", counterpartyId = "" } = useParams();
  const role: Side = roleParam === "BUYER" ? "BUYER" : "SELLER";
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();
  const navigate = useNavigate();
  const canRecord = useHasPermission("bil.payment.record");
  const theirs = (seller: string, buyer: string) => (role === "SELLER" ? buyer : seller) === counterpartyId;

  const exposures = useQuery({ queryKey: ["trading", "exposures", role], queryFn: () => api.exposures(role) });
  const invoices = useQuery({ queryKey: ["trading", "invoices", role], queryFn: () => api.invoices(role) });
  const payments = useQuery({ queryKey: ["trading", "payments", role], queryFn: () => api.payments(role) });

  const exposure = exposures.data?.find((row) => theirs(row.sellerEntityId, row.buyerEntityId));
  const pairInvoices = (invoices.data ?? []).filter((row) => theirs(row.sellerEntityId, row.buyerEntityId));
  const pairPayments = (payments.data ?? []).filter((row) => theirs(row.sellerEntityId, row.buyerEntityId));
  const error = exposures.error ?? invoices.error ?? payments.error;

  return (
    <main className="shell-page">
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <h1>
        {t("trading.account.title").text}: <EntityName entityId={counterpartyId} />
      </h1>
      {error && <p role="alert">{errorText(error, t("trading.error.generic").text)}</p>}
      {exposures.isLoading ? <p>{t("trading.loading").text}</p> : exposure && <ExposurePanel exposure={exposure} />}

      <section className="trading-section">
        <h2>{t(role === "SELLER" ? "trading.invoices.issued.title" : "trading.invoices.received.title").text}</h2>
        {pairInvoices.length === 0 ? (
          <p>{t("trading.invoices.empty").text}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t("trading.invoice.tax_point").text}</th>
                <th>{t("trading.invoice.gross").text}</th>
                <th>{t("trading.invoice.amount_due").text}</th>
                <th>{t("trading.column.payment_state").text}</th>
              </tr>
            </thead>
            <tbody>
              {pairInvoices.map((invoice) => (
                <tr key={invoice.invoiceId}>
                  <td>
                    <Link to={`/trading/invoices/${invoice.invoiceId}`}>{invoice.docNumber}</Link>
                  </td>
                  <td>{formatDate(invoice.taxPointDate)}</td>
                  <td>
                    <MoneyDisplay amount={invoice.grossAmount} />
                  </td>
                  <td>
                    <MoneyDisplay amount={invoice.amountDue ?? invoice.grossAmount} />
                  </td>
                  <td>
                    <StateChip
                      state={paymentStateChip(invoice.paymentState)}
                      label={t(`trading.invoice.payment_state.${invoice.paymentState ?? "OPEN"}`).text}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="trading-section">
        <h2>{t(role === "SELLER" ? "trading.payments.received.title" : "trading.payments.made.title").text}</h2>
        {pairPayments.length === 0 ? (
          <p>{t("trading.payments.empty").text}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t("trading.payment.received_on").text}</th>
                <th>{t("trading.payment.method").text}</th>
                <th>{t("trading.column.amount").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {pairPayments.map((payment) => (
                <tr key={payment.receiptId}>
                  <td>
                    <Link to={`/trading/payments/${payment.receiptId}`}>{payment.docNumber}</Link>
                  </td>
                  <td>{formatDate(payment.receivedOn)}</td>
                  <td>{t(`trading.payment.method.${payment.method}`).text}</td>
                  <td>
                    <MoneyDisplay amount={payment.status === "REVERSAL" ? -payment.amount : payment.amount} />
                  </td>
                  <td>
                    <StateChip state={receiptChip(payment.status)} label={t(`trading.payment.status.${payment.status}`).text} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {role === "SELLER" && canRecord && (
        <PaymentEntry buyerEntityId={counterpartyId} onRecorded={(receipt) => navigate(`/trading/payments/${receipt.receiptId}`)} />
      )}
    </main>
  );
}
