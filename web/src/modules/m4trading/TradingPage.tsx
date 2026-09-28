import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { EntityName } from "./labels";
import { useTradingApi, type Side } from "./tradingApi";
import { deliveryChip, discrepancyChip, errorText, grnChip, orderChip } from "./tradingView";

/**
 * The trading desk (doc 30 section 5.4; 24A section 8, demo scope): one page with the registers a
 * user's job needs, each shown only to a user holding its permission. The buyer's requisition
 * book (own orders), the seller's order desk (orders received), the seller's delivery notes and
 * the receiver's incoming deliveries and goods received notes.
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

  return (
    <main className="shell-page">
      <div className="page-heading">
        <div className="page-heading__icon">
          <svg viewBox="0 0 24 24" width="24" height="24" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><circle cx="9" cy="21" r="1"></circle><circle cx="20" cy="21" r="1"></circle><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"></path></svg>
        </div>
        <div className="page-heading__copy">
          <h1>{t("trading.title").text}</h1>
        </div>
      </div>
      {canOrder && (
        <section>
          <h2>{t("trading.book.title").text}</h2>
          <div className="trading-action-bar">
            <Link className="modern-btn" to="/trading/orders/new">{t("trading.order.new").text}</Link>
          </div>
          <OrderRegister role="BUYER" />
        </section>
      )}
      {canAccept && (
        <section>
          <h2>{t("trading.desk.title").text}</h2>
          <OrderRegister role="SELLER" />
        </section>
      )}
      {(canDraftNote || canIssueNote || canDispatch) && (
        <section>
          <h2>{t("trading.notes.title").text}</h2>
          <DeliveryRegister role="SELLER" />
        </section>
      )}
      {canReceive && (
        <section>
          <h2>{t("trading.incoming.title").text}</h2>
          <DeliveryRegister role="BUYER" />
          <h2>{t("trading.grns.title").text}</h2>
          <GrnRegister role="BUYER" />
        </section>
      )}
      {canInvoice && (
        <section>
          <h2>{t("trading.to_invoice.title").text}</h2>
          <GrnRegister role="SELLER" />
          <h2>{t("trading.invoices.issued.title").text}</h2>
          <InvoiceRegister role="SELLER" />
        </section>
      )}
      {(canInvoice || canCredit) && (
        <section>
          <h2>{t("trading.discrepancies.received.title").text}</h2>
          <DiscrepancyRegister role="SELLER" />
        </section>
      )}
      <section>
        <h2>{t("trading.invoices.received.title").text}</h2>
        <InvoiceRegister role="BUYER" />
      </section>
      {(canReceive || canDispute) && (
        <section>
          <h2>{t("trading.discrepancies.raised.title").text}</h2>
          <DiscrepancyRegister role="BUYER" />
        </section>
      )}
    </main>
  );
}

function OrderRegister({ role }: { role: Side }) {
  const t = useT();
  const api = useTradingApi();
  const orders = useQuery({ queryKey: ["trading", "orders", role], queryFn: () => api.orders(role) });

  if (orders.isLoading) {
    return <p>{t("trading.loading").text}</p>;
  }
  if (orders.isError) {
    return <p role="alert">{errorText(orders.error, t("trading.error.generic").text)}</p>;
  }
  if (!orders.data?.length) {
    return <p>{t("trading.orders.empty").text}</p>;
  }
  return (
    <div className="modern-table-card">
      <div className="modern-table-scroll">
        <table className="modern-table">
          <thead>
            <tr>
              <th>{t("trading.column.number").text}</th>
              <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
              <th>{t("trading.column.status").text}</th>
              <th>{t("trading.column.amount").text}</th>
            </tr>
          </thead>
          <tbody>
            {orders.data.map((order) => (
              <tr key={order.orderId}>
                <td>
                  <Link to={`/trading/orders/${order.orderId}`}>{order.docNumber ?? t("trading.order.draft_number").text}</Link>
                </td>
                <td>
                  <EntityName entityId={role === "BUYER" ? order.sellerEntityId : order.buyerEntityId} />
                </td>
                <td>
                  <StateChip state={orderChip(order.status)} label={t(`trading.order.status.${order.status}`).text} />
                </td>
                <td>
                  <MoneyDisplay amount={order.netAmount} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function DeliveryRegister({ role }: { role: Side }) {
  const t = useT();
  const api = useTradingApi();
  const notes = useQuery({ queryKey: ["trading", "notes", role], queryFn: () => api.deliveryNotes(role) });

  if (notes.isLoading) {
    return <p>{t("trading.loading").text}</p>;
  }
  if (notes.isError) {
    return <p role="alert">{errorText(notes.error, t("trading.error.generic").text)}</p>;
  }
  const rows = (notes.data ?? []).filter((note) => role === "SELLER" || note.status !== "DRAFT");
  if (rows.length === 0) {
    return <p>{t("trading.notes.empty").text}</p>;
  }
  return (
    <div className="modern-table-card">
      <div className="modern-table-scroll">
        <table className="modern-table">
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
                  <Link to={`/trading/delivery-notes/${note.deliveryNoteId}`}>{note.docNumber ?? t("trading.order.draft_number").text}</Link>
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
  );
}

function GrnRegister({ role }: { role: Side }) {
  const t = useT();
  const api = useTradingApi();
  const grns = useQuery({ queryKey: ["trading", "grns", role], queryFn: () => api.grns(role) });

  if (grns.isLoading) {
    return <p>{t("trading.loading").text}</p>;
  }
  if (grns.isError) {
    return <p role="alert">{errorText(grns.error, t("trading.error.generic").text)}</p>;
  }
  if (!grns.data?.length) {
    return <p>{t("trading.grns.empty").text}</p>;
  }
  return (
    <div className="modern-table-card">
      <div className="modern-table-scroll">
        <table className="modern-table">
          <thead>
            <tr>
              <th>{t("trading.column.number").text}</th>
              <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
              <th>{t("trading.column.status").text}</th>
            </tr>
          </thead>
          <tbody>
            {grns.data.map((grn) => (
              <tr key={grn.grnId}>
                <td>
                  <Link to={`/trading/grns/${grn.grnId}`}>{grn.docNumber ?? t("trading.order.draft_number").text}</Link>
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
  );
}

/** The discrepancies raised with the seller (SELLER) or raised by the buyer (BUYER), open first. */
function DiscrepancyRegister({ role }: { role: Side }) {
  const t = useT();
  const api = useTradingApi();
  const rows = useQuery({ queryKey: ["trading", "discrepancies", role], queryFn: () => api.discrepancies(role) });

  if (rows.isLoading) {
    return <p>{t("trading.loading").text}</p>;
  }
  if (rows.isError) {
    return <p role="alert">{errorText(rows.error, t("trading.error.generic").text)}</p>;
  }
  if (!rows.data?.length) {
    return <p>{t("trading.discrepancies.empty").text}</p>;
  }
  const sorted = [...rows.data].sort((a, b) => (a.status === b.status ? 0 : a.status === "RAISED" ? -1 : 1));
  return (
    <div className="modern-table-card">
      <div className="modern-table-scroll">
        <table className="modern-table">
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
                  <Link to={`/trading/discrepancies/${row.discrepancyId}`}>{row.docNumber}</Link>
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
  );
}

function InvoiceRegister({ role }: { role: Side }) {
  const t = useT();
  const api = useTradingApi();
  const invoices = useQuery({ queryKey: ["trading", "invoices", role], queryFn: () => api.invoices(role) });

  if (invoices.isLoading) {
    return <p>{t("trading.loading").text}</p>;
  }
  if (invoices.isError) {
    return <p role="alert">{errorText(invoices.error, t("trading.error.generic").text)}</p>;
  }
  if (!invoices.data?.length) {
    return <p>{t("trading.invoices.empty").text}</p>;
  }
  return (
    <div className="modern-table-card">
      <div className="modern-table-scroll">
        <table className="modern-table">
          <thead>
            <tr>
              <th>{t("trading.column.number").text}</th>
              <th>{t(role === "BUYER" ? "trading.column.seller" : "trading.column.buyer").text}</th>
              <th>{t("trading.invoice.due").text}</th>
              <th>{t("trading.invoice.gross").text}</th>
            </tr>
          </thead>
          <tbody>
            {invoices.data.map((invoice) => (
              <tr key={invoice.invoiceId}>
                <td>
                  <Link to={`/trading/invoices/${invoice.invoiceId}`}>{invoice.docNumber}</Link>
                </td>
                <td>
                  <EntityName entityId={role === "BUYER" ? invoice.sellerEntityId : invoice.buyerEntityId} />
                </td>
                <td>{invoice.dueDate}</td>
                <td>
                  <MoneyDisplay amount={invoice.grossAmount} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
