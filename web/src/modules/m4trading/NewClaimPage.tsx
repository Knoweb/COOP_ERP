import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import "./trading.css";
import { SkuLabel } from "./labels";
import { useTradingApi, type ClaimKind } from "./tradingApi";
import { errorText } from "./tradingView";

const KINDS: ClaimKind[] = ["DAMAGED", "EXPIRED_ON_ARRIVAL", "WRONG_GOODS", "QUALITY"];

/**
 * Raise a claim (doc 24 section 4.5; 24A section 8, "Discrepancy / Claim"; M4-06): the buyer
 * names, on a confirmed goods received note, how much of each line it claims and why. The
 * photographs are added on the claim once it is raised (an upload may take a while on a phone).
 */
export function NewClaimPage() {
  const [params] = useSearchParams();
  const grnId = params.get("grnId") ?? "";
  const t = useT();
  const api = useTradingApi();
  const navigate = useNavigate();
  const key = useIdempotencyKey();
  const [kind, setKind] = useState<ClaimKind>("DAMAGED");
  const [note, setNote] = useState("");
  const [returnRequested, setReturnRequested] = useState(false);
  const [quantities, setQuantities] = useState<Record<string, string>>({});

  const grn = useQuery({ queryKey: ["trading", "grn", grnId], queryFn: () => api.grn(grnId), enabled: grnId !== "" });
  const lines = Object.entries(quantities)
    .map(([grnLineId, text]) => ({ grnLineId, qty: Number(text) }))
    .filter((line) => Number.isFinite(line.qty) && line.qty > 0);

  const raise = useMutation({
    mutationFn: () =>
      api.raiseClaim(
        { grnId, kind, returnRequested, note: note.trim() === "" ? undefined : note.trim(), lines },
        key.current()
      ),
    onSuccess: (claim) => {
      key.next();
      navigate(`/trading/claims/${claim.claimId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  return (
    <main className="shell-page">
      <Link className="back-link" to={grnId ? `/trading/grns/${grnId}` : "/trading"}>
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      <h1>{t("trading.claim.new").text}</h1>
      {grn.isLoading && <p>{t("trading.loading").text}</p>}
      {grn.isError && <p role="alert">{errorText(grn.error, t("trading.error.not_found").text)}</p>}
      {grn.data && (
        <>
          <p>{`${t("trading.grn.title").text} ${grn.data.docNumber ?? ""}`}</p>
          <label className="trading-form-field">
            {t("trading.claim.kind").text}
            <select
              aria-label={t("trading.claim.kind").text}
              value={kind}
              onChange={(event) => setKind(event.target.value as ClaimKind)}
            >
              {KINDS.map((option) => (
                <option key={option} value={option}>
                  {t(`trading.claim.kind.${option}`).text}
                </option>
              ))}
            </select>
          </label>
          <table>
            <thead>
              <tr>
                <th>{t("trading.column.item").text}</th>
                <th>{t("trading.column.received").text}</th>
                <th>{t("trading.claim.claimed").text}</th>
              </tr>
            </thead>
            <tbody>
              {grn.data.lines
                .filter((line) => line.receivedQty > 0)
                .map((line) => (
                  <tr key={line.lineId}>
                    <td>
                      <SkuLabel skuId={line.skuId} />
                    </td>
                    <td>{line.receivedQty}</td>
                    <td>
                      <input
                        inputMode="decimal"
                        aria-label={t("trading.claim.claimed").text}
                        value={quantities[line.lineId] ?? ""}
                        onChange={(event) => setQuantities({ ...quantities, [line.lineId]: event.target.value })}
                      />
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
          <label className="trading-form-field">
            {t("trading.claim.note").text}
            <input type="text" maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
          </label>
          <label>
            <input
              type="checkbox"
              checked={returnRequested}
              onChange={(event) => setReturnRequested(event.target.checked)}
            />
            {t("trading.claim.return_requested").text}
          </label>
          <div className="trading-action-bar">
            <button type="button" disabled={raise.isPending || lines.length === 0} onClick={() => raise.mutate()}>
              {t("trading.claim.raise").text}
            </button>
          </div>
          {raise.isError && <p role="alert">{errorText(raise.error, t("trading.error.generic").text)}</p>}
        </>
      )}
    </main>
  );
}
