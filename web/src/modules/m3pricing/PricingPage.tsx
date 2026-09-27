import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import { chipOf, errorText } from "./priceListState";

/**
 * The trade price lists of the caller's scope (doc 30 section 5.3, "Trade price list"): every
 * version with its state and the date it applies from, a link to each, and a form that starts a
 * new list as a draft. A buyer sees the published versions of the list its relationship binds.
 */
export function PricingPage() {
  const t = useT();
  const api = usePricingApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();
  const canAuthor = useHasPermission("prc.pricelist.author");
  const [name, setName] = useState("");

  const priceLists = useQuery({
    queryKey: ["pricing", "priceLists"],
    queryFn: () => api.listPriceLists("TRADE")
  });

  const create = useMutation({
    mutationFn: () => api.createPriceList(name.trim(), idempotencyKey.current()),
    onSuccess: (list) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["pricing", "priceLists"] });
      navigate(`/pricing/lists/${list.priceListId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <main className="shell-page">
      <h1>{t("pricing.title").text}</h1>

      {canAuthor && (
        <form onSubmit={submit} style={{ display: "flex", gap: "var(--space-2)", alignItems: "end", marginBottom: "var(--space-4)" }}>
          <label style={{ display: "grid", gap: "var(--space-half)" }}>
            {t("pricing.field.name").text}
            <input type="text" value={name} maxLength={120} required onChange={(event) => setName(event.target.value)} />
          </label>
          <button type="submit" disabled={create.isPending || !name.trim()}>
            {t("pricing.create").text}
          </button>
          {create.isError && <p role="alert">{errorText(create.error, t("pricing.error.generic").text)}</p>}
        </form>
      )}

      <section>
        {priceLists.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {priceLists.isError && <p role="alert">{errorText(priceLists.error, t("pricing.error.generic").text)}</p>}
        {priceLists.data?.length === 0 && <p>{t("pricing.list.empty").text}</p>}
        {priceLists.data && priceLists.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>{t("pricing.column.name").text}</th>
                <th>{t("pricing.column.version").text}</th>
                <th>{t("pricing.column.status").text}</th>
                <th>{t("pricing.column.apply_from").text}</th>
              </tr>
            </thead>
            <tbody>
              {priceLists.data.map((list) => (
                <tr key={list.priceListId}>
                  <td>
                    <Link to={`/pricing/lists/${list.priceListId}`}>{list.name}</Link>
                  </td>
                  <td>{list.version}</td>
                  <td>
                    <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
                  </td>
                  <td>{list.applyFrom ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
