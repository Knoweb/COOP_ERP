import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useScope } from "../../shell/scope/useScope";
import { AttachmentCapture } from "../../shell/components/AttachmentCapture";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import "./trading.css";
import { EntityName, SkuLabel } from "./labels";
import { useTradingApi } from "./tradingApi";
import { acceptedLines, claimChip, errorText } from "./tradingView";

/**
 * One claim (24A section 8, "Discrepancy / Claim"; doc 24 sections 3.5 and 4.5; M4-06, demo
 * scope). The buyer raised it against a confirmed goods received note and adds photographs while it
 * waits (an upload may still be verifying). The seller decides once every photograph is verified:
 * it approves what it accepts of each line, which issues the credit note in the same act, with or
 * without the goods coming back, or rejects with a reason. When the seller wants the goods back,
 * the buyer sends them and M5 takes them out of its stock. Each party writes only its own part.
 */
export function ClaimPage() {
  const { claimId = "" } = useParams();
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = useTradingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const canRaise = useHasPermission("del.claim.raise");
  const canDecide = useHasPermission("del.claim.decide");
  const photoKey = useIdempotencyKey();
  const decideKey = useIdempotencyKey();
  const returnKey = useIdempotencyKey();
  const [accepted, setAccepted] = useState<Record<string, string>>({});
  const [findings, setFindings] = useState("");
  const [returnRequired, setReturnRequired] = useState(false);
  const [rejectReason, setRejectReason] = useState("");

  const claim = useQuery({ queryKey: ["trading", "claim", claimId], queryFn: () => api.claim(claimId) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["trading"] });
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const photo = useMutation({
    mutationFn: (file: File) => api.addClaimPhoto(claimId, file, photoKey.current()),
    onSuccess: () => {
      photoKey.next();
      refresh();
    },
    onError: forget(photoKey)
  });
  const approve = useMutation({
    mutationFn: (lines: { claimLineId: string; qty: number }[]) =>
      api.approveClaim(
        claimId,
        { findings: findings.trim() === "" ? undefined : findings.trim(), returnRequired, lines },
        decideKey.current()
      ),
    onSuccess: () => {
      decideKey.next();
      refresh();
    },
    onError: forget(decideKey)
  });
  const reject = useMutation({
    mutationFn: () => api.rejectClaim(claimId, rejectReason.trim(), decideKey.current()),
    onSuccess: () => {
      decideKey.next();
      refresh();
    },
    onError: forget(decideKey)
  });
  const sendBack = useMutation({
    mutationFn: () => api.dispatchClaimReturn(claimId, returnKey.current()),
    onSuccess: () => {
      returnKey.next();
      refresh();
    },
    onError: forget(returnKey)
  });

  if (claim.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (claim.isError || !claim.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(claim.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
          <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
          {t("trading.back").text}
        </Link>
      </main>
    );
  }

  const c = claim.data;
  const isSeller = c.sellerEntityId === scope.entityId;
  const isBuyer = c.buyerEntityId === scope.entityId;
  const open = c.status === "RAISED";
  const evidencePending = c.photos.some((p) => p.status !== "COMPLETE");
  const lines = acceptedLines(c.lines, accepted);
  const nothingAccepted = lines !== null && lines.every((line) => line.qty === 0);

  return (
    <main className="shell-page">
      <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <DocumentHeader
        code={c.docNumber ?? ""}
        title={t("trading.claim.title").text}
        state={{ look: claimChip(c.status), label: t(`trading.claim.status.${c.status}`).text }}
        facts={[
          { label: t("trading.column.buyer").text, value: <EntityName entityId={c.buyerEntityId} /> },
          { label: t("trading.column.seller").text, value: <EntityName entityId={c.sellerEntityId} /> },
          { label: t("trading.claim.kind").text, value: t(`trading.claim.kind.${c.kind}`).text },
          {
            label: t("trading.grn.title").text,
            value: <Link to={`/trading/grns/${c.grnId}`}>{c.grnDocNumber ?? t("trading.grn.open").text}</Link>
          },
          {
            label: t("trading.invoice.title").text,
            value: c.invoiceId && <Link to={`/trading/invoices/${c.invoiceId}`}>{t("trading.invoice.title").text}</Link>
          },
          { label: t("trading.claim.raised_at").text, value: c.raisedAt && formatInstant(c.raisedAt) },
          { label: t("trading.claim.window").text, value: formatInstant(c.windowEndsAt) },
          { label: t("trading.claim.note").text, value: c.note },
          {
            label: t("trading.claim.return_requested").text,
            value: c.returnRequested ? t("trading.claim.yes").text : undefined
          },
          { label: t("trading.claim.decided_at").text, value: c.decidedAt && formatInstant(c.decidedAt) },
          { label: t("trading.claim.findings").text, value: c.findings },
          { label: t("trading.claim.reject_reason").text, value: c.rejectReason },
          {
            label: t("trading.discrepancy.credit_note").text,
            value: c.creditNoteId && (
              <Link to={`/trading/credit-notes/${c.creditNoteId}`}>
                {c.creditNoteDocNumber ?? t("trading.creditnote.title").text}
              </Link>
            )
          },
          { label: t("trading.claim.returned_at").text, value: c.returnedAt && formatInstant(c.returnedAt) }
        ]}
      />

      <table>
        <thead>
          <tr>
            <th>{t("trading.column.item").text}</th>
            <th>{t("trading.column.unit").text}</th>
            <th>{t("trading.claim.claimed").text}</th>
            <th>{t("trading.claim.accepted").text}</th>
            <th>{t("trading.column.tier_price").text}</th>
          </tr>
        </thead>
        <tbody>
          {c.lines.map((line) => (
            <tr key={line.claimLineId}>
              <td>
                <SkuLabel skuId={line.skuId} />
              </td>
              <td>{line.uomCode}</td>
              <td>{line.claimedQty}</td>
              <td>
                {isSeller && open && canDecide ? (
                  <input
                    inputMode="decimal"
                    aria-label={t("trading.claim.accepted").text}
                    placeholder={String(line.claimedQty)}
                    value={accepted[line.claimLineId] ?? ""}
                    onChange={(event) => setAccepted({ ...accepted, [line.claimLineId]: event.target.value })}
                  />
                ) : (
                  line.approvedQty
                )}
              </td>
              <td>{line.unitPrice !== undefined && <MoneyDisplay amount={line.unitPrice} />}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>{t("trading.claim.photos").text}</h2>
      <AttachmentCapture
        attachments={c.photos.map((p, index) => ({
          id: p.attachmentId,
          label: `${index + 1}. ${t(`trading.claim.photo.status.${p.status}`).text}`
        }))}
        addLabel={t("trading.claim.photo.add").text}
        emptyLabel={t("trading.claim.photo.none").text}
        disabled={!open || !isBuyer || !canRaise || photo.isPending}
        onAdd={(file) => photo.mutate(file)}
      />
      {photo.isError && <p role="alert">{errorText(photo.error, t("trading.error.generic").text)}</p>}

      {isSeller && open && canDecide && (
        <section className="trading-section">
          <h2>{t("trading.claim.decide").text}</h2>
          {evidencePending && <p>{t("trading.claim.evidence_pending").text}</p>}
          {!c.invoiceId && <p>{t("trading.claim.invoice_first").text}</p>}
          <label className="trading-form-field">
            {t("trading.claim.findings").text}
            <input type="text" maxLength={500} value={findings} onChange={(event) => setFindings(event.target.value)} />
          </label>
          <label>
            <input
              type="checkbox"
              checked={returnRequired}
              onChange={(event) => setReturnRequired(event.target.checked)}
            />
            {t("trading.claim.return_required").text}
          </label>
          <div className="trading-action-bar">
            <button
              type="button"
              disabled={
                approve.isPending || evidencePending || !c.invoiceId || lines === null || nothingAccepted
              }
              onClick={() => lines && approve.mutate(lines)}
            >
              {t("trading.claim.approve").text}
            </button>
          </div>
          {lines === null && <p role="alert">{t("trading.claim.accepted_invalid").text}</p>}
          <label className="trading-form-field">
            {t("trading.claim.reject_reason").text}
            <input
              type="text"
              maxLength={500}
              value={rejectReason}
              onChange={(event) => setRejectReason(event.target.value)}
            />
          </label>
          <button
            type="button"
            disabled={reject.isPending || evidencePending || rejectReason.trim() === ""}
            onClick={() => reject.mutate()}
          >
            {t("trading.claim.reject").text}
          </button>
          {[approve, reject].map((m, i) =>
            m.isError ? (
              <p key={i} role="alert">
                {errorText(m.error, t("trading.error.generic").text)}
              </p>
            ) : null
          )}
        </section>
      )}

      {isBuyer && canRaise && c.status === "APPROVED" && c.returnRequired && !c.returnedAt && (
        <section className="trading-section">
          <p>{t("trading.claim.return.explain").text}</p>
          <button type="button" disabled={sendBack.isPending} onClick={() => sendBack.mutate()}>
            {t("trading.claim.return").text}
          </button>
          {sendBack.isError && <p role="alert">{errorText(sendBack.error, t("trading.error.generic").text)}</p>}
        </section>
      )}
    </main>
  );
}
