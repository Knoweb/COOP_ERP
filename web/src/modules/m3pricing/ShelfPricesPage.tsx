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
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

/**
 * The society's shelf price list (23A section 8, "Shelf price list"; doc 23 section 3.1: one RETAIL
 * list per society for all its shops, no per-shop deviation): its versions, and the form that
 * starts it when the society has none. The Federation sees its ADVISORY lists here, and starts one.
 */
export function ShelfPricesPage() {
  const t = useT();
  const api = usePricingApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();
  const canAuthor = useHasPermission("prc.pricelist.author");
  const [name, setName] = useState("");
  const [kind, setKind] = useState<"RETAIL" | "ADVISORY">("RETAIL");

  const retail = useQuery({ queryKey: ["pricing", "priceLists", "RETAIL"], queryFn: () => api.listPriceLists("RETAIL") });
  const advisory = useQuery({
    queryKey: ["pricing", "priceLists", "ADVISORY"],
    queryFn: () => api.listPriceLists("ADVISORY")
  });

  const create = useMutation({
    mutationFn: () => api.createPriceList(name.trim(), idempotencyKey.current(), kind),
    onSuccess: (list) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["pricing", "priceLists"] });
      navigate(`/pricing/shelf/${list.priceListId}`);
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

  const lists = [...(retail.data ?? []), ...(advisory.data ?? [])];
  const hasRetail = (retail.data ?? []).length > 0;

  return (
    <main className="shell-page">
      <h1>{t("pricing.shelf.title").text}</h1>
      <PricingTabs />
      <p className="pricing-muted">{t("pricing.shelf.intro").text}</p>

      {canAuthor && !hasRetail && (
        <form onSubmit={submit} className="pricing-filter-bar">
          <label className="modern-field">
            <span className="modern-field__label pricing-filter-label">{t("pricing.shelf.field.kind").text}</span>
            <select value={kind} onChange={(event) => setKind(event.target.value as "RETAIL" | "ADVISORY")}>
              <option value="RETAIL">{t("pricing.kind.retail").text}</option>
              <option value="ADVISORY">{t("pricing.kind.advisory").text}</option>
            </select>
          </label>
          <label className="modern-field">
            <span className="modern-field__label pricing-filter-label">{t("pricing.field.name").text}</span>
            <input type="text" value={name} maxLength={120} required onChange={(event) => setName(event.target.value)} />
          </label>
          <button type="submit" className="modern-btn modern-btn--primary" disabled={create.isPending || !name.trim()}>
            {t("pricing.shelf.create").text}
          </button>
          {create.isError && <p role="alert">{errorText(create.error, t("pricing.error.generic").text)}</p>}
        </form>
      )}

      <section>
        {(retail.isLoading || advisory.isLoading) && <p>{t("pricing.list.loading").text}</p>}
        {retail.isError && <p role="alert">{errorText(retail.error, t("pricing.error.generic").text)}</p>}
        {!retail.isLoading && lists.length === 0 && <p>{t("pricing.shelf.empty").text}</p>}
        {lists.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.column.name").text}</th>
                    <th>{t("pricing.column.kind").text}</th>
                    <th>{t("pricing.column.version").text}</th>
                    <th>{t("pricing.column.status").text}</th>
                    <th>{t("pricing.column.apply_from").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {lists.map((list) => (
                    <tr key={list.priceListId}>
                      <td>
                        <Link to={`/pricing/shelf/${list.priceListId}`}>{list.name}</Link>
                      </td>
                      <td>{t(`pricing.kind.${list.kind.toLowerCase()}`).text}</td>
                      <td>{list.version}</td>
                      <td>
                        <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
                      </td>
                      <td>{list.applyFrom ?? ""}</td>
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
