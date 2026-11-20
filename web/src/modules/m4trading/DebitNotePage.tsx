import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { openServerFile } from "../../shell/api/openServerFile";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { EntityName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { errorText } from "./tradingView";

/**
 * One debit note (24A section 8, "Tax invoice / debit note", demo scope; M4-08): the seller's
 * debit against its invoice, read only by both parties, with its links to the invoice and the
 * discrepancy it settled. Print opens the A4 PDF the worker printed, as for the invoice.
 */
export function DebitNotePage() {
  const { debitNoteId = "" } = useParams();
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = useTradingApi();

  const note = useQuery({
    queryKey: ["trading", "debit-note", debitNoteId],
    queryFn: () => api.debitNote(debitNoteId)
  });
  // openServerFile opens the tab in the click (a popup blocker lets it through), cuts its handle
  // back to this page and follows only an http(s) link to our own origins (as the credit note).
  const print = useMutation({ mutationFn: () => openServerFile(() => api.debitNotePrint(debitNoteId)) });
  const openPrint = () => print.mutate();

  if (note.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (note.isError || !note.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(note.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
          <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
          {t("trading.back").text}
        </Link>
      </main>
    );
  }

  const cn = note.data;

  return (
    <main className="shell-page">
      <Link className="back-link back-pill-btn" to="/trading">
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          stroke="currentColor"
          strokeWidth="2"
          fill="none"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="m12 19-7-7 7-7" />
          <path d="M19 12H5" />
        </svg>
        <span>{t("trading.back").text}</span>
      </Link>
      <DocumentHeader
        code={cn.docNumber ?? ""}
        title={t("trading.debitnote.title").text}
        state={{ look: "issued", label: t("trading.debitnote.status.ISSUED").text }}
        facts={[
          { label: t("trading.column.seller").text, value: <EntityName entityId={cn.sellerEntityId} /> },
          { label: t("trading.column.buyer").text, value: <EntityName entityId={cn.buyerEntityId} /> },
          {
            label: t("trading.debitnote.invoice").text,
            value: (
              <Link className="trading-doc-link" to={`/trading/invoices/${cn.invoiceId}`}>
                {cn.invoiceDocNumber ?? t("trading.invoice.title").text}
              </Link>
            )
          },

          { label: t("trading.debitnote.reason").text, value: cn.reason },
          { label: t("trading.field.issued_at").text, value: cn.issuedAt && formatInstant(cn.issuedAt) }
        ]}
      >
        <div className="trading-header-actions">
          <button
            type="button"
            className="trading-print-btn"
            disabled={print.isPending}
            onClick={openPrint}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <polyline points="6 9 6 2 18 2 18 9" />
              <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
              <rect x="6" y="14" width="12" height="8" />
            </svg>
            <span>{t("trading.debitnote.print").text}</span>
          </button>
        </div>
      </DocumentHeader>
      {print.isError && <p role="alert">{errorText(print.error, t("trading.error.generic").text)}</p>}

      <div className="modern-table-card trading-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table trading-table">
            <thead>
              <tr>
                <th>{t("trading.column.item").text}</th>
                <th>{t("trading.column.unit").text}</th>
                <th>{t("trading.column.qty").text}</th>
                <th className="trading-cell-numeric">{t("trading.column.tier_price").text}</th>
                <th>{t("trading.invoice.vat_rate").text}</th>
                <th className="trading-cell-numeric">{t("trading.invoice.vat").text}</th>
                <th className="trading-cell-numeric">{t("trading.column.amount").text}</th>
              </tr>
            </thead>
            <tbody>
              {cn.lines.map((line) => (
                <tr key={line.lineId}>
                  <td>
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td>{line.uomCode}</td>
                  <td>{line.qty}</td>
                  <td className="trading-cell-numeric">
                    <MoneyDisplay amount={line.unitPrice} />
                  </td>
                  <td>{line.taxRatePercent}</td>
                  <td className="trading-cell-numeric">
                    <MoneyDisplay amount={line.taxAmount} />
                  </td>
                  <td className="trading-cell-numeric">
                    <MoneyDisplay amount={line.lineTotal} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <dl className="document-header__facts trading-section">
        <div className="document-header__fact">
          <dt>{t("trading.invoice.net").text}</dt>
          <dd>
            <MoneyDisplay amount={cn.netAmount} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.invoice.vat").text}</dt>
          <dd>
            <MoneyDisplay amount={cn.taxAmount} />
          </dd>
        </div>
        <div className="document-header__fact">
          <dt>{t("trading.debitnote.gross").text}</dt>
          <dd>
            <MoneyDisplay amount={cn.grossAmount} size="total" />
          </dd>
        </div>
      </dl>
    </main>
  );
}
