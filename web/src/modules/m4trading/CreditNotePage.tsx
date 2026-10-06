import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { openServerFile } from "../../shell/api/openServerFile";
import { useFormatInstant } from "../../shell/i18n/formats";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { EntityName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { errorText } from "./tradingView";

/**
 * One credit note (24A section 8, "Tax invoice / credit note", demo scope; M4-08): the seller's
 * credit against its invoice, read only by both parties, with its links to the invoice and the
 * discrepancy it settled. Print opens the A4 PDF the worker printed, as for the invoice.
 */
export function CreditNotePage() {
  const { creditNoteId = "" } = useParams();
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = useTradingApi();

  const note = useQuery({
    queryKey: ["trading", "credit-note", creditNoteId],
    queryFn: () => api.creditNote(creditNoteId)
  });
  const print = useMutation({ mutationFn: () => openServerFile(() => api.creditNotePrint(creditNoteId)) });
  // The tab is opened in the click itself, so a popup blocker lets it through (as InvoicePage).
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
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <DocumentHeader
        code={cn.docNumber ?? ""}
        title={t("trading.creditnote.title").text}
        state={{ look: "issued", label: t("trading.creditnote.status.ISSUED").text }}
        facts={[
          { label: t("trading.column.seller").text, value: <EntityName entityId={cn.sellerEntityId} /> },
          { label: t("trading.column.buyer").text, value: <EntityName entityId={cn.buyerEntityId} /> },
          {
            label: t("trading.creditnote.invoice").text,
            value: <Link to={`/trading/invoices/${cn.invoiceId}`}>{cn.invoiceDocNumber ?? t("trading.invoice.title").text}</Link>
          },
          {
            label: t("trading.discrepancy.title").text,
            value: cn.discrepancyId && (
              <Link to={`/trading/discrepancies/${cn.discrepancyId}`}>{t("trading.discrepancy.open").text}</Link>
            )
          },
          { label: t("trading.creditnote.reason").text, value: cn.reason },
          { label: t("trading.field.issued_at").text, value: cn.issuedAt && formatInstant(cn.issuedAt) }
        ]}
      >
        <button type="button" disabled={print.isPending} onClick={openPrint}>
          {t("trading.creditnote.print").text}
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
          {cn.lines.map((line) => (
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
          <dt>{t("trading.creditnote.gross").text}</dt>
          <dd>
            <MoneyDisplay amount={cn.grossAmount} size="total" />
          </dd>
        </div>
      </dl>
    </main>
  );
}
