import { useState } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { useT } from "../../shell/i18n/useT";
import { useCustomersApi } from "./customersApi";
import { errorText, nameIn } from "./customersView";
import "./customers.css";

const PAGE_SIZE = 50;

/** Outstanding credit accounts and their ageing within the caller's society. */
export function ReceivablesPage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useCustomersApi();
  const canView = useHasPermission("cus.customer.view");
  const [offset, setOffset] = useState(0);

  const receivables = useQuery({
    queryKey: ["customers", "receivables", offset],
    queryFn: () => api.receivables(offset, PAGE_SIZE + 1),
    enabled: canView
  });

  const rows = receivables.data?.slice(0, PAGE_SIZE) ?? [];
  const hasNext = (receivables.data?.length ?? 0) > PAGE_SIZE;

  return (
    <main className="shell-page">
      <Link to="/customers">{t("customers.back").text}</Link>
      <h1>{t("customers.receivables.title").text}</h1>

      {!canView && (
        <p role="alert">{t("customers.receivables.forbidden").text}</p>
      )}

      {canView && (
        <section className="customers-section">
          <p>{t("customers.receivables.explain").text}</p>

          {receivables.isLoading && <p>{t("customers.loading").text}</p>}
          {receivables.isError && (
            <p role="alert">
              {errorText(
                receivables.error,
                t("customers.error.generic").text
              )}
            </p>
          )}

          {receivables.isSuccess && rows.length === 0 && (
            <p>{t("customers.receivables.empty").text}</p>
          )}

          {receivables.isSuccess && rows.length > 0 && (
            <table>
              <caption>{t("customers.receivables.title").text}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("customers.column.name").text}</th>
                  <th scope="col">{t("customers.column.account").text}</th>
                  <th scope="col">{t("customers.column.balance").text}</th>
                  <th scope="col">{t("customers.ageing.0_30").text}</th>
                  <th scope="col">{t("customers.ageing.31_60").text}</th>
                  <th scope="col">{t("customers.ageing.61_90").text}</th>
                  <th scope="col">{t("customers.ageing.over_90").text}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((entry) => {
                  const name = nameIn(locale, entry);
                  const { account } = entry;

                  return (
                    <tr key={account.accountId}>
                      <td>
                        <Link to={`/customers/${account.customerId}`}>
                          {name.text}
                        </Link>
                        {name.isFallback && <LangFallbackTag />}
                      </td>
                      <td>{account.accountNo}</td>
                      <td><MoneyDisplay amount={account.balance} /></td>
                      <td><MoneyDisplay amount={account.ageing.days0To30} /></td>
                      <td><MoneyDisplay amount={account.ageing.days31To60} /></td>
                      <td><MoneyDisplay amount={account.ageing.days61To90} /></td>
                      <td><MoneyDisplay amount={account.ageing.over90} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}

          {receivables.isSuccess && (offset > 0 || hasNext) && (
            <nav className="customers-filter-bar" aria-label={t("customers.receivables.title").text}>
              <button
                type="button"
                disabled={offset === 0}
                onClick={() => setOffset((value) => Math.max(0, value - PAGE_SIZE))}
              >
                {t("customers.receivables.previous").text}
              </button>
              <span>
                {t("customers.receivables.page").text} {offset / PAGE_SIZE + 1}
              </span>
              <button
                type="button"
                disabled={!hasNext}
                onClick={() => setOffset((value) => value + PAGE_SIZE)}
              >
                {t("customers.receivables.next").text}
              </button>
            </nav>
          )}
        </section>
      )}
    </main>
  );
}
