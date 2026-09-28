import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { usePosApi } from "./posApi";
import { useShop } from "./ShopPicker";
import { SkuLabel, TillLabel, useTenderText } from "./labels";
import { errorText, receiptLook, tillNumber } from "./posView";
import "./pos.css";

/**
 * One receipt as the till issued it (26A section 8, demo scope): its lines, its totals, how it
 * was paid, the session it was sold in, and what central flagged. M6 has no read of a single
 * receipt yet, so the screen finds it in its shop's list (the shop is in the address).
 */
export function ReceiptPage() {
  const { documentId = "" } = useParams();
  const [locationId] = useShop();
  const t = useT();
  const formatInstant = useFormatInstant();
  const formatDate = useFormatDate();
  const tenderText = useTenderText();
  const api = usePosApi();

  const receipts = useQuery({
    queryKey: ["pos", "receipts", locationId],
    queryFn: () => api.receipts(locationId),
    enabled: locationId !== ""
  });
  const sessions = useQuery({
    queryKey: ["pos", "sessions", locationId],
    queryFn: () => api.sessions(locationId),
    enabled: locationId !== ""
  });
  const positions = useQuery({
    queryKey: ["pos", "positions", locationId],
    queryFn: () => api.positions(locationId),
    enabled: locationId !== "",
    retry: false
  });

  const back = (
    <Link className="back-link" to={`/pos?location=${locationId}`}>
      {t("pos.back").text}
    </Link>
  );

  if (receipts.isLoading) {
    return <main className="shell-page">{t("pos.loading").text}</main>;
  }
  const receipt = receipts.data?.find((r) => r.documentId === documentId);
  if (receipts.isError || !receipt) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(receipts.error, t("pos.error.not_found").text)}</p>
        {back}
      </main>
    );
  }

  const session = sessions.data?.find((s) => s.sessionId === receipt.sessionId);
  const look = receiptLook(receipt);

  return (
    <main className="shell-page">
      {back}
      <DocumentHeader
        code={receipt.docNumberDisplay ?? t("pos.receipt.unnumbered").text}
        title={t("pos.receipt.title").text}
        state={{ look, label: t(look === "alert" ? "pos.receipt.flagged" : "pos.receipt.issued").text }}
        facts={[
          { label: t("pos.column.time").text, value: formatInstant(receipt.issuedAt) },
          { label: t("pos.field.business_date").text, value: receipt.businessDate ? formatDate(receipt.businessDate) : undefined },
          { label: t("pos.column.till").text, value: <TillLabel number={tillNumber(positions.data, receipt.tillPositionId)} /> },
          {
            label: t("pos.field.session").text,
            value: session ? (
              <Link className="entity-link" to={`/pos/sessions?location=${locationId}`}>
                {t(session.status === "OPEN" ? "pos.session.opened_at" : "pos.session.closed_at", undefined, {
                  time: formatInstant(session.status === "OPEN" ? session.openedAt : (session.closedAt ?? session.openedAt))
                }).text}
              </Link>
            ) : undefined
          }
        ]}
      />

      {receipt.flags.length > 0 && (
        <p role="status" className="pos-flags">
          {t("pos.receipt.flags", undefined, { flags: receipt.flags.join(", ") }).text}
        </p>
      )}

      <section className="modern-table-card pos-section">
        <h2>{t("pos.receipt.lines").text}</h2>
        <div className="modern-table-scroll">
          <table className="modern-table">
            <thead>
              <tr>
                <th>{t("pos.column.line").text}</th>
                <th>{t("pos.column.item").text}</th>
                <th className="numeric-cell">{t("pos.column.qty").text}</th>
                <th className="numeric-cell">{t("pos.column.unit_price").text}</th>
                <th className="numeric-cell">{t("pos.column.line_total").text}</th>
              </tr>
            </thead>
            <tbody>
              {receipt.lines.map((line) => (
                <tr key={line.lineNo}>
                  <td>{line.lineNo}</td>
                  <td>
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td className="numeric-cell">{line.qty}</td>
                  <td className="numeric-cell">
                    <MoneyDisplay amount={line.unitPrice} />
                  </td>
                  <td className="numeric-cell">
                    <MoneyDisplay amount={line.lineTotal} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="pos-section pos-totals">
        <dl>
          <div>
            <dt>{t("pos.total.net").text}</dt>
            <dd><MoneyDisplay amount={receipt.netAmount} /></dd>
          </div>
          <div>
            <dt>{t("pos.total.tax").text}</dt>
            <dd><MoneyDisplay amount={receipt.taxAmount} /></dd>
          </div>
          <div>
            <dt>{t("pos.total.gross").text}</dt>
            <dd><MoneyDisplay amount={receipt.grossAmount} size="total" /></dd>
          </div>
        </dl>
      </section>

      <section className="pos-section">
        <h2>{t("pos.receipt.tenders").text}</h2>
        {receipt.tenders.length === 0 ? (
          <p>{t("pos.receipt.no_tenders").text}</p>
        ) : (
          <dl className="pos-totals">
            {receipt.tenders.map((tender) => (
              <div key={tender.seq}>
                <dt>{tenderText(tender.kind)}</dt>
                <dd><MoneyDisplay amount={tender.amount} /></dd>
              </div>
            ))}
          </dl>
        )}
      </section>
    </main>
  );
}
