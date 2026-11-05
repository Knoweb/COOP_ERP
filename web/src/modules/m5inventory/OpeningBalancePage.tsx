import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { chipOf, errorText } from "./stockView";
import "./inventory.css";

/**
 * One opening balance (doc 25 section 4.7, flow 6.9, PGL-M2-06): its counted lines and state. A DRAFT is
 * signed by the entity's officer (`inv.opening.sign`); a signed one is countersigned by another
 * person (`inv.opening.countersign`), which issues the OPB document and posts the stock.
 * Both ask for a fresh second factor (the shell's step-up). The server refuses a countersignature
 * by the signer, in its own words.
 *
 * When discrepancies are discovered during pre-check, an authorized higher officer with `inv.opening.sign`
 * can reject the draft or signed balance with a documented reason, leaving historical lines
 * immutable and freeing the onboarding location for re-preparation.
 */
export function OpeningBalancePage() {
  const { openingBalanceId = "" } = useParams();
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canSign = useHasPermission("inv.opening.sign");
  const canCountersign = useHasPermission("inv.opening.countersign");
  const canReject = useHasPermission("inv.opening.sign");
  const signKey = useIdempotencyKey();
  const countersignKey = useIdempotencyKey();
  const rejectKey = useIdempotencyKey();

  const [rejecting, setRejecting] = useState(false);
  const [rejectionReason, setRejectionReason] = useState("");

  const balance = useQuery({
    queryKey: ["inventory", "opening", openingBalanceId],
    queryFn: () => api.openingBalance(openingBalanceId)
  });

  const done = (key: { next: () => void }) => () => {
    key.next();
    queryClient.invalidateQueries({ queryKey: ["inventory"] });
  };
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const sign = useMutation({
    mutationFn: () => api.sign(openingBalanceId, signKey.current()),
    onSuccess: done(signKey),
    onError: forget(signKey)
  });
  const countersign = useMutation({
    mutationFn: () => api.countersign(openingBalanceId, countersignKey.current()),
    onSuccess: done(countersignKey),
    onError: forget(countersignKey)
  });
  const reject = useMutation({
    mutationFn: () => api.rejectOpeningBalance(openingBalanceId, rejectionReason.trim(), rejectKey.current()),
    onSuccess: () => {
      setRejecting(false);
      setRejectionReason("");
      done(rejectKey)();
    },
    onError: forget(rejectKey)
  });

  if (balance.isLoading) {
    return <main className="shell-page">{t("inventory.loading").text}</main>;
  }
  if (balance.isError || !balance.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(balance.error, t("inventory.error.not_found").text)}</p>
        <div>
        <Link className="back-pill-btn" to="/inventory">
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
          {t("inventory.back").text}
        </Link>
      </div>
      </main>
    );
  }

  const ob = balance.data;
  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/inventory">
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
          {t("inventory.back").text}
        </Link>
      </div>
      <h1>{t("inventory.opening.title").text}</h1>
      <p>
        <StateChip state={chipOf(ob.status)} label={t(`inventory.opening.status.${ob.status}`).text} />
      </p>

      {ob.status === "REJECTED" && (
        <aside className="inventory-rejection-banner" role="alert">
          {t("inventory.opening.rejection.banner", undefined, { reason: ob.rejectionReason ?? "" }).text}
        </aside>
      )}

      <section className="modern-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table stock-table opening-balance-table opening-balance-table--view">
            <colgroup>
              <col className="col-item" />
              <col className="col-condition" />
              <col className="col-qty" />
              <col className="col-unit-cost" />
            </colgroup>
            <thead>
              <tr>
                <th>{t("inventory.column.item").text}</th>
                <th>{t("inventory.column.condition").text}</th>
                <th className="numeric-cell">{t("inventory.column.qty").text}</th>
                <th className="numeric-cell">{t("inventory.column.unit_cost").text}</th>
              </tr>
            </thead>
            <tbody>
              {ob.lines.map((line) => (
                <tr key={line.lineNo}>
                  <td>
                    <SkuLabel skuId={line.skuId} />
                  </td>
                  <td>
                    <span className="condition-chip">{t(`inventory.condition.${line.condition}`).text}</span>
                  </td>
                  <td className="numeric-cell">{line.qty}</td>
                  <td className="numeric-cell">
                    <MoneyDisplay amount={line.unitCost} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="inventory-action-bar">
        {ob.status === "DRAFT" && canSign && !rejecting && (
          <button type="button" disabled={sign.isPending} onClick={() => sign.mutate()}>
            {t("inventory.opening.sign").text}
          </button>
        )}
        {ob.status === "SIGNED_ENTITY" && canCountersign && !rejecting && (
          <button type="button" disabled={countersign.isPending} onClick={() => countersign.mutate()}>
            {t("inventory.opening.countersign").text}
          </button>
        )}
        {(ob.status === "DRAFT" || ob.status === "SIGNED_ENTITY") && canReject && !rejecting && (
          <button type="button" className="btn-secondary" onClick={() => setRejecting(true)}>
            {t("inventory.opening.reject").text}
          </button>
        )}

        {rejecting && (
          <div className="inventory-reject-form">
            <label className="inventory-form-field">
              {t("inventory.opening.reject.reason").text}
              <input
                type="text"
                value={rejectionReason}
                onChange={(e) => setRejectionReason(e.target.value)}
                placeholder={t("inventory.opening.reject.placeholder").text}
                aria-label={t("inventory.opening.reject.reason").text}
              />
            </label>
            <div className="inventory-action-row">
              <button
                type="button"
                className="btn-danger"
                disabled={reject.isPending || rejectionReason.trim().length < 5}
                onClick={() => reject.mutate()}
              >
                {t("inventory.opening.reject.confirm").text}
              </button>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => {
                  setRejecting(false);
                  setRejectionReason("");
                }}
              >
                {t("inventory.opening.reject.cancel").text}
              </button>
            </div>
            {reject.isError && <p role="alert">{errorText(reject.error, t("inventory.error.generic").text)}</p>}
          </div>
        )}

        {ob.status === "SIGNED_ENTITY" && !rejecting && <p>{t("inventory.opening.countersign.other_person").text}</p>}
      </section>
      {sign.isError && <p role="alert">{errorText(sign.error, t("inventory.error.generic").text)}</p>}
      {countersign.isError && <p role="alert">{errorText(countersign.error, t("inventory.error.generic").text)}</p>}
    </main>
  );
}
