import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import "./trading.css";
import { EntityName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { discrepancyChip, errorText } from "./tradingView";

/**
 * One discrepancy (24A section 8, "Discrepancy / Claim", demo scope; M4-08): raised by the buyer's
 * GRN when the count differs from what was sent. Both parties read it. The seller's accounts
 * settle it by accepting the buyer's count: the invoice bills the received quantity, so a short
 * quantity is settled with no money, and only damaged quantity the invoice charged is credited by a
 * credit note issued with the settlement. Both then see it settled, by whom and when. The two-step propose/accept, escalation and claims come later (docs/progress, M4-08 deferred).
 */
export function DiscrepancyPage() {
  const { discrepancyId = "" } = useParams();
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = useTradingApi();
  const scope = useScope();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canCredit = useHasPermission("bil.creditnote.issue");
  const key = useIdempotencyKey();
  const [reason, setReason] = useState<string | null>(null);

  const discrepancy = useQuery({
    queryKey: ["trading", "discrepancy", discrepancyId],
    queryFn: () => api.discrepancy(discrepancyId)
  });
  const settle = useMutation({
    mutationFn: () =>
      api.settleDiscrepancy(discrepancyId, reason ?? t("trading.discrepancy.reason_default").text, key.current()),
    onSuccess: (settled) => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
      // A credit note was issued only for damaged quantity the invoice charged: show it then.
      if (settled.creditNoteId) {
        navigate(`/trading/credit-notes/${settled.creditNoteId}`);
      }
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  if (discrepancy.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (discrepancy.isError || !discrepancy.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(discrepancy.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
          <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
          {t("trading.back").text}
        </Link>
      </main>
    );
  }

  const d = discrepancy.data;
  const isSeller = d.sellerEntityId === scope.entityId;
  const open = d.status === "RAISED";
  // Damaged quantity was billed and is credited against the invoice, so it waits for the invoice;
  // a short quantity was never billed and is settled with no money at any time.
  const hasDamaged = d.lines.some((line) => line.damagedQty > 0);

  return (
    <main className="shell-page">
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <DocumentHeader
        code={d.docNumber ?? ""}
        title={t("trading.discrepancy.title").text}
        state={{ look: discrepancyChip(d.status), label: t(`trading.discrepancy.status.${d.status}`).text }}
        facts={[
          { label: t("trading.column.buyer").text, value: <EntityName entityId={d.buyerEntityId} /> },
          { label: t("trading.column.seller").text, value: <EntityName entityId={d.sellerEntityId} /> },
          { label: t("trading.discrepancy.kind").text, value: t(`trading.discrepancy.kind.${d.kind}`).text },
          {
            label: t("trading.grn.title").text,
            value: <Link to={`/trading/grns/${d.grnId}`}>{d.grnDocNumber ?? t("trading.grn.open").text}</Link>
          },
          {
            label: t("trading.invoice.title").text,
            value: d.invoiceId && <Link to={`/trading/invoices/${d.invoiceId}`}>{t("trading.invoice.title").text}</Link>
          },
          { label: t("trading.discrepancy.window").text, value: formatInstant(d.windowEndsAt) },
          { label: t("trading.discrepancy.settled_at").text, value: d.settledAt && formatInstant(d.settledAt) },
          { label: t("trading.creditnote.reason").text, value: d.settlementReason },
          {
            label: t("trading.discrepancy.credit_note").text,
            value: d.creditNoteId && (
              <Link to={`/trading/credit-notes/${d.creditNoteId}`}>
                {d.creditNoteDocNumber ?? t("trading.creditnote.title").text}
              </Link>
            )
          }
        ]}
      />

      <table>
        <thead>
          <tr>
            <th>{t("trading.column.item").text}</th>
            <th>{t("trading.column.unit").text}</th>
            <th>{t("trading.column.expected").text}</th>
            <th>{t("trading.column.received").text}</th>
            <th>{t("trading.column.damaged").text}</th>
            <th>{t("trading.discrepancy.variance").text}</th>
            <th>{t("trading.column.tier_price").text}</th>
          </tr>
        </thead>
        <tbody>
          {d.lines.map((line) => (
            <tr key={line.lineId}>
              <td>{line.skuId && <SkuLabel skuId={line.skuId} />}</td>
              <td>{line.uomCode}</td>
              <td>{line.expectedQty}</td>
              <td>{line.receivedQty}</td>
              <td>{line.damagedQty}</td>
              <td>{line.varianceQty}</td>
              <td>{line.unitPrice !== undefined && <MoneyDisplay amount={line.unitPrice} />}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {isSeller && open && canCredit && (
        <section className="trading-section">
          {d.invoiceId || !hasDamaged ? (
            <>
              <p>{t("trading.discrepancy.settle.explain").text}</p>
              <label>
                {t("trading.creditnote.reason").text}
                <input
                  type="text"
                  maxLength={500}
                  value={reason ?? t("trading.discrepancy.reason_default").text}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
              <button
                type="button"
                disabled={settle.isPending || (reason !== null && reason.trim() === "")}
                onClick={() => settle.mutate()}
              >
                {t("trading.discrepancy.settle").text}
              </button>
            </>
          ) : (
            <p>{t("trading.discrepancy.invoice_first").text}</p>
          )}
          {settle.isError && <p role="alert">{errorText(settle.error, t("trading.error.generic").text)}</p>}
        </section>
      )}
    </main>
  );
}
