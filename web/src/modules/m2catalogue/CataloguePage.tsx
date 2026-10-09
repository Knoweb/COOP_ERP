import { useState } from "react";
import { Link } from "react-router-dom";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useCatalogueApi, type SkuStatus } from "./catalogueApi";
import { chipOf, errorText, languageOf, nameIn } from "./skuView";
import "./catalogue.css";

const STATUSES: SkuStatus[] = ["DRAFT", "LOCAL", "SHARED", "INACTIVE"];

function SkuThumbnail({ skuId }: { skuId: string }) {
  const t = useT();
  const api = useCatalogueApi();
  const images = useQuery({ 
    queryKey: ["catalogue", "images", skuId], 
    queryFn: () => api.images(skuId),
    staleTime: 60000 // Cache for a minute to reduce network calls
  });
  
  const activeImage = images.data?.find(img => img.status === "ACTIVE" || img.status === "PENDING");
  
  // Read local preview if available, but clear it if backend image is ready
  let localPreviewUrl = null;
  try {
    localPreviewUrl = sessionStorage.getItem(`sku_preview_${skuId}`);
    if (localPreviewUrl && (activeImage?.thumbUrl || activeImage?.imageUrl)) {
      sessionStorage.removeItem(`sku_preview_${skuId}`);
      localPreviewUrl = null;
    }
  } catch {
    // Ignore storage errors
  }

  const displayUrl = localPreviewUrl || activeImage?.thumbUrl || activeImage?.imageUrl;

  if (images.isLoading && !displayUrl) {
    return <div className="catalogue-thumbnail-placeholder" />;
  }

  if (displayUrl) {
    return (
      <div className="catalogue-thumbnail-container">
        <img src={displayUrl} alt={t("catalogue.images.thumb_alt").text} className="catalogue-thumbnail-image" />
      </div>
    );
  }

  return (
    <div className="catalogue-thumbnail-placeholder">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
        <circle cx="8.5" cy="8.5" r="1.5"></circle>
        <polyline points="21 15 16 10 5 21"></polyline>
      </svg>
    </div>
  );
}

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
      <PageHeader
        icon="catalogue"
        title={t("catalogue.title").text}
        actions={
          canCreate ? (
            <Link className="action-link action-link--primary" to="/catalogue/skus/new">
              <span className="action-link__symbol" aria-hidden="true">+</span>
              <span>{t("catalogue.new").text}</span>
            </Link>
          ) : undefined
        }
      />

      <section className="modern-filter-panel">
        <label className="modern-field modern-field--search">
          <span className="modern-field__label">
            {t("catalogue.filter.search").text}
          </span>

          <span className="modern-control modern-control--search">
            <svg
              className="modern-control__leading-icon"
              viewBox="0 0 24 24"
              aria-hidden="true"
              focusable="false"
            >
              <circle cx="11" cy="11" r="6" />
              <path d="m16 16 4 4" />
            </svg>

            <input
              type="search"
              value={q}
              maxLength={200}
              onChange={(event) => setQ(event.target.value)}
            />
          </span>
        </label>

        <label className="modern-field modern-field--select">
          <span className="modern-field__label">
            {t("catalogue.filter.status").text}
          </span>

          <span className="modern-select">
            <select
              value={status}
              onChange={(event) =>
                setStatus(event.target.value as SkuStatus | "")
              }
            >
              <option value="">
                {t("catalogue.filter.status.all").text}
              </option>

              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {t(`catalogue.status.${value}`).text}
                </option>
              ))}
            </select>

            <svg
              className="modern-select__arrow"
              viewBox="0 0 24 24"
              aria-hidden="true"
              focusable="false"
            >
              <path d="m7 9 5 5 5-5" />
            </svg>
          </span>
        </label>
      </section>

      {skus.isLoading && <p>{t("catalogue.loading").text}</p>}

      {skus.isError && (
        <p role="alert">
          {errorText(skus.error, t("catalogue.error.generic").text)}
        </p>
      )}

      {skus.data?.length === 0 && (
        <p>{t("catalogue.list.empty").text}</p>
      )}

      {skus.data && skus.data.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table catalogue-table">
              <thead>
                <tr>
                  <th>{t("catalogue.column.image").text}</th>
                  <th>{t("catalogue.column.code").text}</th>
                  <th>{t("catalogue.column.name").text}</th>
                  <th>{t("catalogue.column.unit").text}</th>
                  <th>{t("catalogue.column.status").text}</th>
                </tr>
              </thead>

              <tbody>
                {skus.data.map((sku) => (
                  <tr key={sku.skuId} style={{ verticalAlign: 'middle' }}>
                    <td style={{ padding: 'var(--space-2)' }}>
                      <SkuThumbnail skuId={sku.skuId} />
                    </td>
                    <td>
                      <Link
                        className="entity-link"
                        to={`/catalogue/skus/${sku.skuId}`}
                      >
                        <span className="entity-link__icon" aria-hidden="true">
                          <svg viewBox="0 0 24 24">
                            <path d="M4 7 12 3l8 4-8 4-8-4Z" />
                            <path d="M4 7v10l8 4 8-4V7" />
                            <path d="M12 11v10" />
                          </svg>
                        </span>

                        {sku.skuCode}
                      </Link>
                    </td>

                    <td className="table-primary-text">
                      {nameIn(sku, language)}
                    </td>

                    <td>{sku.baseUomCode}</td>

                    <td>
                      <StateChip
                        state={chipOf(sku.status)}
                        label={t(`catalogue.status.${sku.status}`).text}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}