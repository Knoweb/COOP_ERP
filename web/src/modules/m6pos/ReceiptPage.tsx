import { useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { usePosApi } from "./posApi";
import type { ReceiptLineProfit } from "./posApi";
import { useShop } from "./ShopPicker";
import { FlagList, SkuLabel, TillLabel, useTenderText } from "./labels";
import { errorText, receiptLook, tillNumber } from "./posView";
import "./pos.css";

/**
 * One receipt as the till issued it (26A section 8): its lines, its totals, how it was paid, the
 * session it was sold in, and what central flagged, in the reader's language. Read on its own
 * (wave 2, M6-08), never found in the shop's whole list; the shop in the address is for Back.
 */
export function ReceiptPage() {
  const { documentId = "" } = useParams();
  const [locationId] = useShop();
  const t = useT();
  const formatInstant = useFormatInstant();
  const formatDate = useFormatDate();
  const tenderText = useTenderText();
  const api = usePosApi();

  const found = useQuery({
    queryKey: ["pos", "receipt", documentId],
    queryFn: () => api.receipt(documentId),
    enabled: documentId !== "",
    retry: false
  });
  const profitRead = useQuery({
    queryKey: ["pos", "receipt", "profit", documentId],
    queryFn: () => api.receiptProfit(documentId),
    enabled: documentId !== "",
    retry: false
  });
  const profit = profitRead.data;
  const lineProfitMap = useMemo(() => {
    const map = new Map<number, ReceiptLineProfit>();
    if (profit?.lines) {
      for (const line of profit.lines) {
        map.set(line.lineNo, line);
      }
    }
    return map;
  }, [profit]);

  const sessionId = found.data?.sessionId;
  const sessionRead = useQuery({
    queryKey: ["pos", "session", sessionId],
    queryFn: () => api.session(sessionId!),
    enabled: sessionId !== undefined,
    retry: false
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

  if (found.isLoading) {
    return <main className="shell-page">{t("pos.loading").text}</main>;
  }
  const receipt = found.data;
  if (found.isError || !receipt) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(found.error, t("pos.error.not_found").text)}</p>
        {back}
      </main>
    );
  }

  const session = sessionRead.data;
  const sessionDay = receipt.businessDate ? `&day=${receipt.businessDate}` : "";
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
              <Link className="entity-link" to={`/pos/sessions?location=${locationId}${sessionDay}`}>
                {/* An open session has its open; a closed one its close (an orphan close has no open). */}
                {t(session.status === "OPEN" ? "pos.session.opened_at" : "pos.session.closed_at", undefined, {
                  time: formatInstant((session.status === "OPEN" ? session.openedAt : session.closedAt)!)
                }).text}
              </Link>
            ) : undefined
          }
        ]}
      />

      {receipt.flags.length > 0 && (
        <section role="status" className="pos-flags">
          <p>{t("pos.receipt.flags.heading").text}</p>
          <FlagList flags={receipt.flags} />
        </section>
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
                {profit && (
                  <>
                    <th className="numeric-cell">{t("pos.column.unit_cost").text}</th>
                    <th className="numeric-cell">{t("pos.column.line_cost").text}</th>
                    <th className="numeric-cell">{t("pos.column.line_profit").text}</th>
                    <th className="numeric-cell">{t("pos.column.margin").text}</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {receipt.lines.map((line) => {
                const lp = lineProfitMap.get(line.lineNo);
                return (
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
                    {profit && (
                      lp && !lp.costPending && lp.unitCost != null && lp.lineCost != null && lp.lineProfit != null ? (
                        <>
                          <td className="numeric-cell">
                            <MoneyDisplay amount={lp.unitCost} />
                          </td>
                          <td className="numeric-cell">
                            <MoneyDisplay amount={lp.lineCost} />
                          </td>
                          <td className="numeric-cell">
                            <MoneyDisplay amount={lp.lineProfit} />
                          </td>
                          <td className="numeric-cell">
                            {lp.marginPercent != null ? `${lp.marginPercent}%` : "—"}
                          </td>
                        </>
                      ) : (
                        <td className="numeric-cell pos-profit-pending" colSpan={4}>
                          {t("pos.profit.cost_pending").text}
                        </td>
                      )
                    )}
                  </tr>
                );
              })}
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

      {profit && (
        <section className="pos-section pos-profit-card">
          <h2>{t("pos.profit.title").text}</h2>
          {profit.costPending && (
            <p className="pos-profit-pending" role="status">
              {t("pos.profit.pending_notice").text}
            </p>
          )}
          <dl className="pos-totals">
            <div>
              <dt>{t("pos.profit.total_cost").text}</dt>
              <dd>
                {profit.totalCost != null ? (
                  <MoneyDisplay amount={profit.totalCost} />
                ) : (
                  <span className="pos-profit-pending">{t("pos.profit.cost_pending").text}</span>
                )}
              </dd>
            </div>
            <div>
              <dt>{t("pos.profit.gross_profit").text}</dt>
              <dd>
                {profit.grossProfit != null ? (
                  <MoneyDisplay amount={profit.grossProfit} />
                ) : (
                  <span className="pos-profit-pending">{t("pos.profit.cost_pending").text}</span>
                )}
              </dd>
            </div>
            <div>
              <dt>{t("pos.profit.margin_pct").text}</dt>
              <dd>
                {profit.marginPercent != null ? (
                  <span>{profit.marginPercent}%</span>
                ) : (
                  <span className="pos-profit-pending">{t("pos.profit.cost_pending").text}</span>
                )}
              </dd>
            </div>
          </dl>
        </section>
      )}

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
