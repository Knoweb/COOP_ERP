import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { EntityName } from "./labels";
import { useTradingApi, type Side } from "./tradingApi";
import {
  deliveryChip,
  claimChip,
  discrepancyChip,
  errorText,
  grnChip,
  orderChip,
  paymentStateChip,
  percentOfLimit,
  receiptChip
} from "./tradingView";
import "./trading.css";

type RegisterIconKind = "orders" | "delivery" | "grn" | "invoice" | "discrepancy" | "claim" | "account" | "payment";

/**
 * The trading desk (doc 30 section 5.4; 24A section 8, demo scope): one page with the registers a
 * user's job needs, each shown only to a user holding its permission. The buyer's requisition
 * book (own orders), the seller's order desk (orders received), the seller's delivery notes and
 * the receiver's incoming deliveries and goods received notes. M4-07 and M4-09: the payments
 * received and made, and the accounts (exposure against the credit limit) both ways.
 */
export function TradingPage() {
  const t = useT();
  const canOrder = useHasPermission("ord.order.draft");
  const canAccept = useHasPermission("ord.order.accept");
  const canDraftNote = useHasPermission("del.note.draft");
  const canIssueNote = useHasPermission("del.note.issue");
  const canDispatch = useHasPermission("del.note.dispatch");
  const canReceive = useHasPermission("shop.grn.confirm");
  const canInvoice = useHasPermission("bil.invoice.issue");
  const canCredit = useHasPermission("bil.creditnote.issue");
  const canDispute = useHasPermission("bil.invoice.dispute");
  const canPay = useHasPermission("bil.payment.record");
  const canRaiseClaim = useHasPermission("del.claim.raise");
  const canDecideClaim = useHasPermission("del.claim.decide");

  return (
    <main className="shell-page trading-page">
      <PageHeader
        icon={
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="9" cy="21" r="1" />
            <circle cx="20" cy="21" r="1" />
            <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
          </svg>
        }
        title={t("trading.title").text}
        actions={
          canOrder ? (
            <Link className="modern-btn trading-new-order-btn" to="/trading/orders/new">
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path d="M12 5v14" />
                <path d="M5 12h14" />
              </svg>
              <span>{t("trading.order.new").text}</span>
            </Link>
          ) : undefined
        }
      />

      <div className="trading-registers-stack">
        {canOrder && <OrderRegister role="BUYER" title={t("trading.book.title").text} />}
        {canAccept && <OrderRegister role="SELLER" title={t("trading.desk.title").text} />}
        {(canDraftNote || canIssueNote || canDispatch) && (
          <DeliveryRegister role="SELLER" title={t("trading.notes.title").text} />
        )}
        {canReceive && (
          <>
            <DeliveryRegister role="BUYER" title={t("trading.incoming.title").text} />
            <GrnRegister role="BUYER" title={t("trading.grns.title").text} />
          </>
        )}
        {canInvoice && (
          <>
            <GrnRegister role="SELLER" title={t("trading.to_invoice.title").text} />
            <InvoiceRegister role="SELLER" title={t("trading.invoices.issued.title").text} />
          </>
        )}
        {(canInvoice || canCredit) && (
          <DiscrepancyRegister role="SELLER" title={t("trading.discrepancies.received.title").text} />
        )}
        {canDecideClaim && <ClaimRegister role="SELLER" title={t("trading.claims.received.title").text} />}
        {(canInvoice || canPay) && (
          <>
            <AccountRegister role="SELLER" title={t("trading.accounts.buyers.title").text} />
            <PaymentRegister role="SELLER" title={t("trading.payments.received.title").text} />
          </>
        )}
        <InvoiceRegister role="BUYER" title={t("trading.invoices.received.title").text} />
        <PaymentRegister role="BUYER" title={t("trading.payments.made.title").text} />
        <AccountRegister role="BUYER" title={t("trading.accounts.sellers.title").text} />
        {(canReceive || canDispute) && (
          <DiscrepancyRegister role="BUYER" title={t("trading.discrepancies.raised.title").text} />
        )}
        {canRaiseClaim && <ClaimRegister role="BUYER" title={t("trading.claims.raised.title").text} />}
      </div>
    </main>
  );
}

function RegisterIcon({ kind }: { kind: RegisterIconKind }) {
  if (kind === "orders") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
        <rect x="8" y="2" width="8" height="4" rx="1" />
        <path d="M9 12h6" />
        <path d="M9 16h4" />
      </svg>
    );
  }
  if (kind === "delivery") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M3 7h11v9H3V7Z" />
        <path d="M14 10h4l3 3v3h-7v-6Z" />
        <circle cx="7" cy="18" r="2" />
        <circle cx="18" cy="18" r="2" />
      </svg>
    );
  }
  if (kind === "grn") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M3 7 12 3l9 4-9 4-9-4Z" />
        <path d="M3 7v10l9 4 9-4V7" />
        <path d="m9 14 2 2 4-4" />
      </svg>
    );
  }
  if (kind === "invoice") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <line x1="8" y1="13" x2="16" y2="13" />
        <line x1="8" y1="17" x2="13" y2="17" />
      </svg>
    );
  }
  if (kind === "discrepancy") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="m10.29 3.86-7.5 13A2 2 0 0 0 4.53 20h14.94a2 2 0 0 0 1.74-3.14l-7.5-13a2 2 0 0 0-3.42 0Z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </svg>
    );
  }
  if (kind === "claim") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <line x1="12" y1="8" x2="12" y2="12" />
        <line x1="12" y1="16" x2="12.01" y2="16" />
      </svg>
    );
  }
  if (kind === "payment") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <rect x="2" y="5" width="20" height="14" rx="2" />
        <line x1="2" y1="10" x2="22" y2="10" />
        <path d="M6 15h4" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M3 21h18" />
      <path d="M5 21V7l7-4 7 4v14" />
      <path d="M9 12h1" />
      <path d="M14 12h1" />
      <path d="M9 16h1" />
      <path d="M14 16h1" />
    </svg>
  );
}

function RegisterCard({
  title,
  icon,
  count,
  hasAlert = false,
  isLoading,
  isError,
  errorMessage,
  emptyMessage,
  children
}: {
  title: string;
  icon: RegisterIconKind;
  count?: number;
  hasAlert?: boolean;
  isLoading: boolean;
  isError: boolean;
  errorMessage?: string;
  emptyMessage: string;
  children?: ReactNode;
}) {
  const t = useT();
  const pillClass =
    count === undefined
      ? ""
      : hasAlert && count > 0
        ? "trading-count-pill trading-count-pill--alert"
        : count > 0
          ? "trading-count-pill trading-count-pill--active"
          : "trading-count-pill trading-count-pill--zero";

  return (
    <section className="trading-register-card">
      <div className="trading-register-card__header">
        <div className="trading-register-card__heading">
          <span className="trading-register-card__icon" aria-hidden="true">
            <RegisterIcon kind={icon} />
          </span>
          <h2 className="trading-register-card__title">{title}</h2>
        </div>
        {count !== undefined && (
          <div className="trading-register-card__meta">
            <span className={pillClass} aria-hidden="true">
              {count}
            </span>
          </div>
        )}
      </div>

      <div className="trading-register-card__body">
        {isLoading && (
          <div className="trading-state-box">
            <p>{t("trading.loading").text}</p>
          </div>
        )}
        {isError && (
          <div className="trading-feedback--error">
            <span className="trading-feedback__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
            </span>
            <p role="alert" className="trading-feedback__text">
              {errorMessage}
            </p>
          </div>
        )}
        {!isLoading && !isError && count === 0 && (
          <div className="trading-empty-box">
            <span className="trading-empty-box__icon" aria-hidden="true">
              <RegisterIcon kind={icon} />
            </span>
            <p className="trading-empty-box__text">{emptyMessage}</p>
          </div>
        )}
        {!isLoading && !isError && count !== undefined && count > 0 && children}
      </div>
    </section>
  );
}

function OrderRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const api = useTradingApi();
  const orders = useQuery({ queryKey: ["trading", "orders", role], queryFn: () => api.orders(role) });
  const list = orders.data ?? [];

  return (
    <RegisterCard
      title={title}
      icon="orders"
      count={orders.isSuccess ? list.length : undefined}
      isLoading={orders.isLoading}
      isError={orders.isError}
      errorMessage={orders.isError ? errorText(orders.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.orders.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.column.status").text}</th>
                <th className="trading-cell-numeric">{t("trading.column.amount").text}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((order) => (
                <tr key={order.orderId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/orders/${order.orderId}`}>
                      {order.docNumber ?? t("trading.order.draft_number").text}
                    </Link>
                  </td>
                  <td>
                    <EntityName entityId={role === "BUYER" ? order.sellerEntityId : order.buyerEntityId} />
                  </td>
                  <td>
                    <StateChip state={orderChip(order.status)} label={t(`trading.order.status.${order.status}`).text} />
                  </td>
                  <td className="trading-cell-numeric">
                    <MoneyDisplay amount={order.netAmount} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

function DeliveryRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const api = useTradingApi();
  const notes = useQuery({ queryKey: ["trading", "notes", role], queryFn: () => api.deliveryNotes(role) });
  const rows = (notes.data ?? []).filter((note) => role === "SELLER" || note.status !== "DRAFT");

  return (
    <RegisterCard
      title={title}
      icon="delivery"
      count={notes.isSuccess ? rows.length : undefined}
      isLoading={notes.isLoading}
      isError={notes.isError}
      errorMessage={notes.isError ? errorText(notes.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.notes.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((note) => (
                <tr key={note.deliveryNoteId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/delivery-notes/${note.deliveryNoteId}`}>
                      {note.docNumber ?? t("trading.order.draft_number").text}
                    </Link>
                  </td>
                  <td>
                    <EntityName entityId={role === "BUYER" ? note.sellerEntityId : note.buyerEntityId} />
                  </td>
                  <td>
                    <StateChip state={deliveryChip(note.status)} label={t(`trading.note.status.${note.status}`).text} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

function GrnRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const api = useTradingApi();
  const grns = useQuery({ queryKey: ["trading", "grns", role], queryFn: () => api.grns(role) });
  const list = grns.data ?? [];

  return (
    <RegisterCard
      title={title}
      icon="grn"
      count={grns.isSuccess ? list.length : undefined}
      isLoading={grns.isLoading}
      isError={grns.isError}
      errorMessage={grns.isError ? errorText(grns.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.grns.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((grn) => (
                <tr key={grn.grnId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/grns/${grn.grnId}`}>
                      {grn.docNumber ?? t("trading.order.draft_number").text}
                    </Link>
                  </td>
                  <td>
                    {role === "BUYER"
                      ? grn.sellerEntityId && <EntityName entityId={grn.sellerEntityId} />
                      : <EntityName entityId={grn.receiverEntityId} />}
                  </td>
                  <td>
                    <StateChip state={grnChip(grn.status)} label={t(`trading.grn.status.${grn.status}`).text} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

/** The discrepancies raised with the seller (SELLER) or raised by the buyer (BUYER), open first. */
function DiscrepancyRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const api = useTradingApi();
  const rows = useQuery({ queryKey: ["trading", "discrepancies", role], queryFn: () => api.discrepancies(role) });
  const sorted = [...(rows.data ?? [])].sort((a, b) => (a.status === b.status ? 0 : a.status === "RAISED" ? -1 : 1));
  const openCount = sorted.filter((row) => row.status === "RAISED").length;

  return (
    <RegisterCard
      title={title}
      icon="discrepancy"
      count={rows.isSuccess ? sorted.length : undefined}
      hasAlert={openCount > 0}
      isLoading={rows.isLoading}
      isError={rows.isError}
      errorMessage={rows.isError ? errorText(rows.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.discrepancies.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.discrepancy.kind").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((row) => (
                <tr key={row.discrepancyId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/discrepancies/${row.discrepancyId}`}>
                      {row.docNumber}
                    </Link>
                  </td>
                  <td>
                    <EntityName entityId={role === "BUYER" ? row.sellerEntityId : row.buyerEntityId} />
                  </td>
                  <td>{t(`trading.discrepancy.kind.${row.kind}`).text}</td>
                  <td>
                    <StateChip state={discrepancyChip(row.status)} label={t(`trading.discrepancy.status.${row.status}`).text} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

/** The claims raised with the seller (SELLER) or raised by the buyer (BUYER), open first (M4-06). */
function ClaimRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const api = useTradingApi();
  const rows = useQuery({ queryKey: ["trading", "claims", role], queryFn: () => api.claims(role) });
  const sorted = [...(rows.data ?? [])].sort((a, b) => (a.status === b.status ? 0 : a.status === "RAISED" ? -1 : 1));
  const openCount = sorted.filter((row) => row.status === "RAISED").length;

  return (
    <RegisterCard
      title={title}
      icon="claim"
      count={rows.isSuccess ? sorted.length : undefined}
      hasAlert={openCount > 0}
      isLoading={rows.isLoading}
      isError={rows.isError}
      errorMessage={rows.isError ? errorText(rows.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.claims.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.claim.kind").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((row) => (
                <tr key={row.claimId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/claims/${row.claimId}`}>
                      {row.docNumber}
                    </Link>
                  </td>
                  <td>
                    <EntityName entityId={role === "BUYER" ? row.sellerEntityId : row.buyerEntityId} />
                  </td>
                  <td>{t(`trading.claim.kind.${row.kind}`).text}</td>
                  <td>
                    <StateChip state={claimChip(row.status)} label={t(`trading.claim.status.${row.status}`).text} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

/** The payments received (SELLER) or made (BUYER), with bounced receipts and their reversals, newest first. */
function PaymentRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();
  const rows = useQuery({ queryKey: ["trading", "payments", role], queryFn: () => api.payments(role) });
  const list = rows.data ?? [];

  return (
    <RegisterCard
      title={title}
      icon="payment"
      count={rows.isSuccess ? list.length : undefined}
      isLoading={rows.isLoading}
      isError={rows.isError}
      errorMessage={rows.isError ? errorText(rows.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.payments.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.payment.received_on").text}</th>
                <th>{t("trading.payment.method").text}</th>
                <th className="trading-cell-numeric">{t("trading.column.amount").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((row) => (
                <tr key={row.receiptId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/payments/${row.receiptId}`}>
                      {row.docNumber}
                    </Link>
                  </td>
                  <td>
                    <EntityName entityId={role === "BUYER" ? row.sellerEntityId : row.buyerEntityId} />
                  </td>
                  <td className="trading-cell-date">{formatDate(row.receivedOn)}</td>
                  <td>{t(`trading.payment.method.${row.method}`).text}</td>
                  <td className="trading-cell-numeric">
                    <MoneyDisplay amount={row.status === "REVERSAL" ? -row.amount : row.amount} />
                  </td>
                  <td>
                    <StateChip state={receiptChip(row.status)} label={t(`trading.payment.status.${row.status}`).text} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

/**
 * The exposure of each relationship beside its credit limit (M4-09): the seller's buyers
 * (SELLER) or the buyer's suppliers (BUYER). Each row opens the account of that relationship.
 */
function AccountRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const api = useTradingApi();
  const rows = useQuery({ queryKey: ["trading", "exposures", role], queryFn: () => api.exposures(role) });
  const list = rows.data ?? [];
  const hasWarn = list.some((row) => row.warnThresholdPercent !== undefined && row.warnThresholdPercent !== null);

  return (
    <RegisterCard
      title={title}
      icon="account"
      count={rows.isSuccess ? list.length : undefined}
      hasAlert={hasWarn}
      isLoading={rows.isLoading}
      isError={rows.isError}
      errorMessage={rows.isError ? errorText(rows.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.accounts.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th className="trading-cell-numeric">{t("trading.exposure.amount").text}</th>
                <th className="trading-cell-numeric">{t("trading.exposure.limit").text}</th>
                <th>{t("trading.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((row) => {
                const counterparty = role === "BUYER" ? row.sellerEntityId : row.buyerEntityId;
                const percent = percentOfLimit(row.amount, row.creditLimit);
                return (
                  <tr key={row.relationshipId}>
                    <td>
                      <Link className="trading-doc-link" to={`/trading/accounts/${role}/${counterparty}`}>
                        <EntityName entityId={counterparty} />
                      </Link>
                    </td>
                    <td className="trading-cell-numeric">
                      <MoneyDisplay amount={row.amount} />
                    </td>
                    <td className="trading-cell-numeric">
                      {row.creditLimit === undefined || row.creditLimit === null ? (
                        t("trading.exposure.no_limit").text
                      ) : (
                        <MoneyDisplay amount={row.creditLimit} />
                      )}
                    </td>
                    <td>
                      {row.warnThresholdPercent !== undefined && row.warnThresholdPercent !== null ? (
                        <StateChip
                          state="alert"
                          label={t("trading.exposure.warning", undefined, { percent: row.warnThresholdPercent }).text}
                        />
                      ) : (
                        percent !== null && t("trading.exposure.percent", undefined, { percent }).text
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </RegisterCard>
  );
}

function InvoiceRegister({ role, title }: { role: Side; title: string }) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useTradingApi();
  const invoices = useQuery({ queryKey: ["trading", "invoices", role], queryFn: () => api.invoices(role) });
  const list = invoices.data ?? [];

  return (
    <RegisterCard
      title={title}
      icon="invoice"
      count={invoices.isSuccess ? list.length : undefined}
      isLoading={invoices.isLoading}
      isError={invoices.isError}
      errorMessage={invoices.isError ? errorText(invoices.error, t("trading.error.generic").text) : undefined}
      emptyMessage={t("trading.invoices.empty").text}
    >
      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.number").text}</th>
                <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
                <th>{t("trading.invoice.due").text}</th>
                <th className="trading-cell-numeric">{t("trading.invoice.gross").text}</th>
                <th className="trading-cell-numeric">{t("trading.invoice.amount_due").text}</th>
                <th>{t("trading.column.payment_state").text}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((invoice) => (
                <tr key={invoice.invoiceId}>
                  <td>
                    <Link className="trading-doc-link" to={`/trading/invoices/${invoice.invoiceId}`}>
                      {invoice.docNumber}
                    </Link>
                  </td>
                  <td>
                    <EntityName entityId={role === "BUYER" ? invoice.sellerEntityId : invoice.buyerEntityId} />
                  </td>
                  <td className="trading-cell-date">{formatDate(invoice.dueDate)}</td>
                  <td className="trading-cell-numeric">
                    <MoneyDisplay amount={invoice.grossAmount} />
                  </td>
                  <td className="trading-cell-numeric">
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
        </div>
      </div>
    </RegisterCard>
  );
}
