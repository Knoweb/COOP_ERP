import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { usePricingApi } from "./pricingApi";

/**
 * The price lists of the caller's scope (23A section 8): name, version, state and the date a
 * version applies from. The trade list editor (doc 30 section 5.3) is the next step.
 */
export function PricingPage() {
  const t = useT();
  const api = usePricingApi();

  const priceLists = useQuery({
    queryKey: ["pricing", "priceLists"],
    queryFn: () => api.listPriceLists()
  });

  return (
    <main className="shell-page">
      <h1>{t("pricing.title").text}</h1>
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
                  <td>{list.name}</td>
                  <td>{list.version}</td>
                  <td>{list.status}</td>
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

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}
