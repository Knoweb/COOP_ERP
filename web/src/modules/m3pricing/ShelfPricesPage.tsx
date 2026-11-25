import { useEffect, useState } from "react";
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
import type { PriceList } from "./pricingApi";
import { chipOf, errorText } from "./priceListState";
import { PricingSelect } from "./PricingSelect";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

/**
 * The society's shelf price list (23A section 8, "Shelf price list"; doc 23 section 3.1: one RETAIL
 * list per society for all its shops, no per-shop deviation): its versions, and the form that
 * starts it when the society has none. The Federation sees its ADVISORY lists here, and starts one.
 * An entity may also author a COMMERCIAL price list (M1-03) without altering stock valuation.
 */
export function ShelfPricesPage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();
  const withdrawKey = useIdempotencyKey();
  const canAuthor = useHasPermission("prc.pricelist.author");
  const [name, setName] = useState("");
  const [kind, setKind] = useState<PriceList["kind"]>("RETAIL");

  const retail = useQuery({ queryKey: ["pricing", "priceLists", "RETAIL"], queryFn: () => api.listPriceLists("RETAIL") });
  const commercial = useQuery({
    queryKey: ["pricing", "priceLists", "COMMERCIAL"],
    queryFn: () => api.listPriceLists("COMMERCIAL")
  });
  const advisory = useQuery({
    queryKey: ["pricing", "priceLists", "ADVISORY"],
    queryFn: () => api.listPriceLists("ADVISORY")
  });

  const lists = [...(retail.data ?? []), ...(commercial.data ?? []), ...(advisory.data ?? [])].filter(
    (list) => list.status !== "WITHDRAWN"
  );
  const hasRetail = (retail.data ?? []).length > 0;
  const hasCommercial = (commercial.data ?? []).length > 0;
  const canCreate = canAuthor && (!hasRetail || !hasCommercial);

  useEffect(() => {
    if (hasRetail && kind === "RETAIL" && !hasCommercial) {
      setKind("COMMERCIAL");
    }
  }, [hasRetail, hasCommercial, kind]);

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

  const withdraw = useMutation({
    mutationFn: (listId: string) =>
      api.withdrawDraft(listId, { reason: t("pricing.draft.remove_reason").text }, withdrawKey.current()),
    onSuccess: () => {
      withdrawKey.next();
      queryClient.invalidateQueries({ queryKey: ["pricing", "priceLists"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        withdrawKey.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <main className="shell-page">
      <PageHeader icon="shelf" title={t("pricing.shelf.title").text} />
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.shelf.intro").text}</p>

      {canCreate && (
        <form onSubmit={submit} className="pricing-filter-bar pricing-shelf-create-bar">
          <label className="modern-field pricing-shelf-kind-field">
            <span className="modern-field__label pricing-filter-label">{t("pricing.shelf.field.kind").text}</span>
            <PricingSelect
              ariaLabel={t("pricing.shelf.field.kind").text}
              value={kind}
              onChange={(val) => setKind(val as PriceList["kind"])}
              options={[
                ...(!hasRetail ? [{ value: "RETAIL", label: t("pricing.kind.retail").text }] : []),
                ...(!hasCommercial ? [{ value: "COMMERCIAL", label: t("pricing.kind.commercial").text }] : []),
                { value: "ADVISORY", label: t("pricing.kind.advisory").text }
              ]}
            />
          </label>
          <label className="modern-field pricing-shelf-name-field">
            <span className="modern-field__label pricing-filter-label">{t("pricing.field.name").text}</span>
            <input type="text" value={name} maxLength={120} required onChange={(event) => setName(event.target.value)} />
          </label>
          <button type="submit" className="modern-btn modern-btn--primary" disabled={create.isPending || !name.trim()}>
            {t("pricing.shelf.create").text}
          </button>
          {create.isError && <p role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(create.error, t("pricing.error.generic").text)}</p>}
        </form>
      )}

      <section>
        {(retail.isLoading || commercial.isLoading || advisory.isLoading) && <p>{t("pricing.list.loading").text}</p>}
        {retail.isError && <p role="alert">{errorText(retail.error, t("pricing.error.generic").text)}</p>}
        {commercial.isError && <p role="alert">{errorText(commercial.error, t("pricing.error.generic").text)}</p>}
        {withdraw.isError && (
          <div className="pricing-alert-box pricing-alert-box--error" role="alert">
            <p>{errorText(withdraw.error, t("pricing.error.generic").text)}</p>
          </div>
        )}
        {!retail.isLoading && !commercial.isLoading && lists.length === 0 && (
          <div className="modern-empty-state">{t("pricing.shelf.empty").text}</div>
        )}
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
                        <div className="pricing-list-name-cell">
                          <Link to={`/pricing/shelf/${list.priceListId}`}>{list.name}</Link>
                          {canAuthor && list.status === "DRAFT" && (
                            <button
                              type="button"
                              className="pricing-row-remove-btn"
                              aria-label={`${t("pricing.remove").text} ${list.name}`}
                              disabled={withdraw.isPending}
                              onClick={() => withdraw.mutate(list.priceListId)}
                            >
                              <svg
                                viewBox="0 0 24 24"
                                width="14"
                                height="14"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2.5"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                aria-hidden="true"
                                focusable="false"
                              >
                                <line x1="18" y1="6" x2="6" y2="18" />
                                <line x1="6" y1="6" x2="18" y2="18" />
                              </svg>
                            </button>
                          )}
                        </div>
                      </td>
                      <td>{t(`pricing.kind.${list.kind.toLowerCase()}`).text}</td>
                      <td>{list.version}</td>
                      <td>
                        <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
                      </td>
                      <td>{list.applyFrom ? formatDate(list.applyFrom) : ""}</td>
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
