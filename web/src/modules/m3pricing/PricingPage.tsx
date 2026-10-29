import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import { chipOf, errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

/**
 * The trade price lists of the caller's scope (doc 30 section 5.3, "Trade price list"): every
 * version with its state and the date it applies from, a link to each, and a form that starts a
 * new list as a draft. A buyer sees the published versions of the list its relationship binds.
 */
export function PricingPage() {
  const t = useT();
  const formatDate = useFormatDate();
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
      <PageHeader icon="pricing" title={t("pricing.title").text} />

      <PricingTabs />

      {canAuthor && (
        <section className="pricing-card pricing-create-card">
          <form onSubmit={submit} className="pricing-filter-bar">
            <label className="modern-field pricing-create-field">
              <span className="modern-field__label pricing-filter-label">{t("pricing.field.name").text}</span>
              <input
                type="text"
                className="pricing-input"
                value={name}
                maxLength={120}
                required
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <button type="submit" className="modern-btn modern-btn--primary" disabled={create.isPending || !name.trim()}>
              {t("pricing.create").text}
            </button>
            {create.isError && (
              <p role="alert" className="pricing-alert-box pricing-alert-box--error">
                {errorText(create.error, t("pricing.error.generic").text)}
              </p>
            )}
          </form>
        </section>
      )}

      <section className="pricing-section">
        {priceLists.isLoading && (
          <div className="pricing-card pricing-loading-card">
            <p className="pricing-muted">{t("pricing.list.loading").text}</p>
          </div>
        )}
        {priceLists.isError && (
          <div className="pricing-alert-box pricing-alert-box--error" role="alert">
            <p>{errorText(priceLists.error, t("pricing.error.generic").text)}</p>
          </div>
        )}
        {priceLists.data?.length === 0 && (
          <div className="pricing-card pricing-empty-card">
            <p className="pricing-muted">{t("pricing.list.empty").text}</p>
          </div>
        )}
        {priceLists.data && priceLists.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.column.name").text}</th>
                    <th className="pricing-cell-center">{t("pricing.column.version").text}</th>
                    <th>{t("pricing.column.status").text}</th>
                    <th>{t("pricing.column.apply_from").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {priceLists.data.map((list) => (
                    <tr key={list.priceListId}>
                      <td className="pricing-cell-item">
                        <Link to={`/pricing/lists/${list.priceListId}`} className="pricing-list-link">
                          {list.name}
                        </Link>
                      </td>
                      <td className="pricing-cell-center pricing-version-text">{list.version}</td>
                      <td>
                        <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
                      </td>
                      <td className="pricing-cell-date">{list.applyFrom ? formatDate(list.applyFrom) : ""}</td>
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
