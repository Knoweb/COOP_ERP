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

/**
 * One opening balance (doc 25 section 4.7, flow 6.9): its counted lines and state. A DRAFT is
 * signed by the entity's officer (`inv.opening.sign`); a signed one is countersigned by another
 * person (`inv.opening.countersign`), which issues the OPB document and posts the stock. Both ask
 * for a fresh second factor (the shell's step-up). The server refuses a countersignature by the
 * signer, in its own words.
 */
export function OpeningBalancePage() {
  const { openingBalanceId = "" } = useParams();
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canSign = useHasPermission("inv.opening.sign");
  const canCountersign = useHasPermission("inv.opening.countersign");
  const signKey = useIdempotencyKey();
  const countersignKey = useIdempotencyKey();

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

  if (balance.isLoading) {
    return <main className="shell-page">{t("inventory.loading").text}</main>;
  }
  if (balance.isError || !balance.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(balance.error, t("inventory.error.not_found").text)}</p>
        <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      </main>
    );
  }

  const ob = balance.data;
  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <h1>{t("inventory.opening.title").text}</h1>
      <p>
        <StateChip state={chipOf(ob.status)} label={t(`inventory.opening.status.${ob.status}`).text} />
      </p>

      <table>
        <thead>
          <tr>
            <th>{t("inventory.column.item").text}</th>
            <th>{t("inventory.column.condition").text}</th>
            <th>{t("inventory.column.qty").text}</th>
            <th>{t("inventory.column.unit_cost").text}</th>
          </tr>
        </thead>
        <tbody>
          {ob.lines.map((line) => (
            <tr key={line.lineNo}>
              <td>
                <SkuLabel skuId={line.skuId} />
              </td>
              <td>{t(`inventory.condition.${line.condition}`).text}</td>
              <td>{line.qty}</td>
              <td>
                <MoneyDisplay amount={line.unitCost} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <section className="inventory-action-bar">
        {ob.status === "DRAFT" && canSign && (
          <button type="button" disabled={sign.isPending} onClick={() => sign.mutate()}>
            {t("inventory.opening.sign").text}
          </button>
        )}
        {ob.status === "SIGNED_ENTITY" && canCountersign && (
          <button type="button" disabled={countersign.isPending} onClick={() => countersign.mutate()}>
            {t("inventory.opening.countersign").text}
          </button>
        )}
        {ob.status === "SIGNED_ENTITY" && <p>{t("inventory.opening.countersign.other_person").text}</p>}
      </section>
      {sign.isError && <p role="alert">{errorText(sign.error, t("inventory.error.generic").text)}</p>}
      {countersign.isError && <p role="alert">{errorText(countersign.error, t("inventory.error.generic").text)}</p>}
    </main>
  );
}
