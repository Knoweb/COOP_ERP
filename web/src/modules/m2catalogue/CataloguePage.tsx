import { useState } from "react";
import { Link } from "react-router-dom";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { useCatalogueApi, type SkuStatus } from "./catalogueApi";
import { chipOf, errorText, languageOf, nameIn } from "./skuView";

const STATUSES: SkuStatus[] = ["DRAFT", "LOCAL", "SHARED", "INACTIVE"];

/**
 * The catalogue browser (doc 30 section 5.2; 22A section 8, "Catalogue browser"): the items the
 * caller's scope sees, searched by code or by a name in any of the three languages, with their
 * state, and the way to a new local item. Sorting follows the reader's language (the server's
 * collation).
 */
export function CataloguePage() {
  const t = useT();
  const intl = useIntl();
  const api = useCatalogueApi();
  const canCreate = useHasPermission("cat.sku.create_local");
  const language = languageOf(intl.locale);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<SkuStatus | "">("");

  const skus = useQuery({
    queryKey: ["catalogue", "skus", q.trim(), language, status],
    queryFn: () => api.listSkus(q.trim(), language, status || undefined)
  });

  return (
    <main className="shell-page">
      <h1>{t("catalogue.title").text}</h1>

      <div style={{ display: "flex", gap: "var(--space-2)", alignItems: "end", marginBottom: "var(--space-3)" }}>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("catalogue.filter.search").text}
          <input type="search" value={q} maxLength={200} onChange={(event) => setQ(event.target.value)} />
        </label>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("catalogue.filter.status").text}
          <select value={status} onChange={(event) => setStatus(event.target.value as SkuStatus | "")}>
            <option value="">{t("catalogue.filter.status.all").text}</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {t(`catalogue.status.${value}`).text}
              </option>
            ))}
          </select>
        </label>
        {canCreate && <Link to="/catalogue/skus/new">{t("catalogue.new").text}</Link>}
      </div>

      {skus.isLoading && <p>{t("catalogue.loading").text}</p>}
      {skus.isError && <p role="alert">{errorText(skus.error, t("catalogue.error.generic").text)}</p>}
      {skus.data?.length === 0 && <p>{t("catalogue.list.empty").text}</p>}
      {skus.data && skus.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("catalogue.column.code").text}</th>
              <th>{t("catalogue.column.name").text}</th>
              <th>{t("catalogue.column.unit").text}</th>
              <th>{t("catalogue.column.status").text}</th>
            </tr>
          </thead>
          <tbody>
            {skus.data.map((sku) => (
              <tr key={sku.skuId}>
                <td>
                  <Link to={`/catalogue/skus/${sku.skuId}`}>{sku.skuCode}</Link>
                </td>
                <td>{nameIn(sku, language)}</td>
                <td>{sku.baseUomCode}</td>
                <td>
                  <StateChip state={chipOf(sku.status)} label={t(`catalogue.status.${sku.status}`).text} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
