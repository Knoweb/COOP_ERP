import { useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { usePricingApi } from "./pricingApi";
import type { MrpPolicy, Sku } from "./pricingApi";
import { SkuName, SkuPicker } from "./SkuPicker";
import { errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

type PolicyKind = MrpPolicy["policy"];

/**
 * The multi-MRP policy (23A section 8, "MRP policy"; doc 23 section 3.4): how the printed MRP of
 * the batches on a shop's shelf bounds an item's selling price. Lowest MRP (the default), the
 * scanned batch's MRP, or the cashier picks the batch when the MRPs differ by more than a gap. The
 * table lists the items whose policy is not the default: the society's own, and the Federation's
 * where the society has none. Setting one replaces the society's policy for the item.
 */
export function MrpPolicyPage() {
  const t = useT();
  const api = usePricingApi();
  const queryClient = useQueryClient();
  const canSet = useHasPermission("prc.mrp_policy.set");
  const policies = useQuery({ queryKey: ["pricing", "mrpPolicies"], queryFn: () => api.listMrpPolicies() });

  return (
    <main className="shell-page">
      <h1>{t("pricing.policy.title").text}</h1>
      <PricingTabs />
      <p className="pricing-muted">{t("pricing.policy.intro").text}</p>

      {canSet && <SetPolicyForm onSet={() => queryClient.invalidateQueries({ queryKey: ["pricing"] })} />}

      <section className="pricing-section">
        {policies.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {policies.isError && <p role="alert">{errorText(policies.error, t("pricing.error.generic").text)}</p>}
        {policies.data?.length === 0 && <p>{t("pricing.policy.empty").text}</p>}
        {policies.data && policies.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.column.item").text}</th>
                    <th>{t("pricing.column.policy").text}</th>
                    <th>{t("pricing.column.gap").text}</th>
                    <th>{t("pricing.column.set_by").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {policies.data.map((policy) => (
                    <tr key={`${policy.skuId}/${policy.source}`}>
                      <td>
                        <SkuName skuId={policy.skuId} />
                      </td>
                      <td>{t(`pricing.policy.${policy.policy.toLowerCase()}`).text}</td>
                      <td>
                        {policy.gapAmount != null && <MoneyDisplay amount={policy.gapAmount} />}
                        {policy.gapPercent != null && (
                          <span> {t("pricing.policy.gap_percent", undefined, { percent: policy.gapPercent }).text}</span>
                        )}
                      </td>
                      <td>{t(`pricing.policy.source.${policy.source.toLowerCase()}`).text}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

/** Choose an item and its policy; the gaps only for the picker. */
function SetPolicyForm({ onSet }: { onSet: () => void }) {
  const t = useT();
  const api = usePricingApi();
  const key = useIdempotencyKey();
  const [sku, setSku] = useState<Sku | null>(null);
  const [policy, setPolicy] = useState<PolicyKind>("AUTO_LOWEST");
  const [gapAmount, setGapAmount] = useState("");
  const [gapPercent, setGapPercent] = useState("");

  const set = useMutation({
    mutationFn: () =>
      api.setMrpPolicy(
        {
          skuId: sku!.skuId,
          policy,
          gapAmount: policy === "PICKER" && gapAmount ? Number(gapAmount) : null,
          gapPercent: policy === "PICKER" && gapPercent ? Number(gapPercent) : null
        },
        key.current()
      ),
    onSuccess: () => {
      key.next();
      setSku(null);
      onSet();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    set.mutate();
  };

  return (
    <section className="pricing-section" aria-labelledby="pricing-policy-set">
      <h2 id="pricing-policy-set">{t("pricing.policy.set").text}</h2>
      {sku ? (
        <p className="pricing-header-row">
          <SkuName skuId={sku.skuId} />
          <button type="button" onClick={() => setSku(null)}>
            {t("pricing.gazette.change_item").text}
          </button>
        </p>
      ) : (
        <SkuPicker onPick={setSku} />
      )}
      <form onSubmit={submit} className="pricing-form-grid">
        <label className="pricing-form-field">
          {t("pricing.column.policy").text}
          <select value={policy} onChange={(event) => setPolicy(event.target.value as PolicyKind)}>
            <option value="AUTO_LOWEST">{t("pricing.policy.auto_lowest").text}</option>
            <option value="BARCODE_RESOLVED">{t("pricing.policy.barcode_resolved").text}</option>
            <option value="PICKER">{t("pricing.policy.picker").text}</option>
          </select>
        </label>
        {policy === "PICKER" && (
          <>
            <label className="pricing-form-field">
              {t("pricing.policy.field.gap_amount").text}
              <input type="number" min="0" step="0.01" value={gapAmount} onChange={(e) => setGapAmount(e.target.value)} />
            </label>
            <label className="pricing-form-field">
              {t("pricing.policy.field.gap_percent").text}
              <input type="number" min="0" max="100" step="0.01" value={gapPercent} onChange={(e) => setGapPercent(e.target.value)} />
            </label>
          </>
        )}
        <button type="submit" disabled={set.isPending || !sku}>
          {t("pricing.policy.submit").text}
        </button>
      </form>
      {set.isSuccess && <p role="status">{t("pricing.policy.saved").text}</p>}
      {set.isError && <p role="alert">{errorText(set.error, t("pricing.error.generic").text)}</p>}
    </section>
  );
}
