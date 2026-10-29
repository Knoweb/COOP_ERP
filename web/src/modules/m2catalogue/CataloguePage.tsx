import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { DropdownSelect } from "../../shell/components/DropdownSelect";
import { useCatalogueApi, type SkuStatus } from "./catalogueApi";
import { chipOf, errorText, languageOf, nameIn } from "./skuView";
import "./catalogue.css";

const STATUSES: SkuStatus[] = ["DRAFT", "LOCAL", "SHARED", "INACTIVE"];

/** Custom Status dropdown with styled selection, checkmark, and accessible popover */
function SkuStatusDropdown(props: {
  value: SkuStatus | "";
  onChange: (value: SkuStatus | "") => void;
  anyLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const t = useT();

  const options: { value: SkuStatus | ""; label: string }[] = [
    { value: "", label: props.anyLabel },
    ...STATUSES.map((status) => ({
      value: status,
      label: t(`catalogue.status.${status}`).text
    }))
  ];

  const currentOption = options.find((opt) => opt.value === props.value) ?? options[0];

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  return (
    <div className="custom-status-dropdown" ref={containerRef}>
      <button
        type="button"
        className="custom-status-dropdown__trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="custom-status-dropdown__label">{currentOption.label}</span>
        <svg
          className="custom-status-dropdown__arrow"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="m7 9 5 5 5-5" />
        </svg>
      </button>

      {open && (
        <ul className="custom-status-dropdown__menu" role="listbox">
          {options.map((opt) => {
            const isSelected = props.value === opt.value;
            return (
              <li key={opt.value} role="presentation">
                <button
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  className={
                    isSelected
                      ? "custom-status-dropdown__option custom-status-dropdown__option--selected"
                      : "custom-status-dropdown__option"
                  }
                  onClick={() => {
                    props.onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <span className="custom-status-dropdown__option-label">{opt.label}</span>
                  {isSelected && (
                    <span className="custom-status-dropdown__check" aria-hidden="true">
                      <svg
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      >
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

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
  const canImport = useHasPermission("cat.import.run");
  const canSupplier = useHasPermission("cat.supplier.manage");
  const language = languageOf(intl.locale);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<SkuStatus | "">("");
  const [filtersOpen, setFiltersOpen] = useState(false);

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
          <div className="catalogue-actions">
            {canSupplier && (
              <Link className="action-link catalogue-action-btn catalogue-action-btn--secondary" to="/catalogue/suppliers">
                <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                  <circle cx="9" cy="7" r="4" />
                  <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
                  <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                </svg>
                <span>{t("catalogue.suppliers.nav").text}</span>
              </Link>
            )}
            {canCreate && (
              <Link className="action-link catalogue-action-btn catalogue-action-btn--secondary" to="/catalogue/suspects">
                <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                  <line x1="9" y1="15" x2="15" y2="15" />
                </svg>
                <span>{t("catalogue.suspects.nav").text}</span>
              </Link>
            )}
            {canImport && (
              <Link className="action-link catalogue-action-btn catalogue-action-btn--secondary" to="/catalogue/import">
                <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="17 8 12 3 7 8" />
                  <line x1="12" y1="3" x2="12" y2="15" />
                </svg>
                <span>{t("catalogue.import.nav").text}</span>
              </Link>
            )}
            {canCreate && (
              <Link className="action-link action-link--primary catalogue-action-btn catalogue-action-btn--primary" to="/catalogue/skus/new">
                <span className="catalogue-action-btn__symbol" aria-hidden="true">+</span>
                <span>{t("catalogue.new").text}</span>
              </Link>
            )}
          </div>
        }
      />

      <button
        type="button"
        className="filter-toggle"
        onClick={() => setFiltersOpen(!filtersOpen)}
        aria-expanded={filtersOpen}
        aria-controls="catalogue-filters"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M4 7h16M7 12h10M10 17h4" />
        </svg>
        {t("catalogue.filter.toggle").text}
        <span aria-hidden="true">{filtersOpen ? "▴" : "▾"}</span>
      </button>

      {filtersOpen && (
        <section id="catalogue-filters" className="catalogue-filter-panel">
          <label className="modern-field catalogue-filter-field">
            <span className="modern-field__label catalogue-filter-label">
              {t("catalogue.filter.search").text}
            </span>

            <span className="catalogue-search-wrapper">
              <svg
                className="catalogue-search-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <circle cx="11" cy="11" r="6" />
                <path d="m16 16 4 4" />
              </svg>

              <input
                type="search"
                className="catalogue-filter-input"
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
            <DropdownSelect
              value={status}
              onChange={(val) => setStatus(val as SkuStatus | "")}
              options={[
                { value: "", label: t("catalogue.filter.status.all").text },
                ...STATUSES.map((value) => ({
                  value,
                  label: t(`catalogue.status.${value}`).text
                }))
              ]}
            />
          </label>
        </section>
      )}

      {skus.isLoading && (
        <div className="catalogue-state-card catalogue-state-card--loading">
          <div className="catalogue-state-spinner" aria-hidden="true" />
          <p>{t("catalogue.loading").text}</p>
        </div>
      )}

      {skus.isError && (
        <div className="catalogue-state-card catalogue-state-card--error" role="alert">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <p>{errorText(skus.error, t("catalogue.error.generic").text)}</p>
        </div>
      )}

      {skus.data?.length === 0 && (
        <div className="catalogue-state-card catalogue-state-card--empty">
          <div className="catalogue-empty-icon" aria-hidden="true">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="m7.5 4.27 9 5.15" />
              <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
              <path d="m3.3 7 8.7 5 8.7-5" />
              <path d="M12 22V12" />
            </svg>
          </div>
          <p className="catalogue-empty-text">{t("catalogue.list.empty").text}</p>
        </div>
      )}

      {skus.data && skus.data.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table catalogue-table">
              <thead>
                <tr>
                  <th scope="col">{t("catalogue.column.image").text}</th>
                  <th scope="col">{t("catalogue.column.code").text}</th>
                  <th scope="col">{t("catalogue.column.name").text}</th>
                  <th scope="col">{t("catalogue.column.unit").text}</th>
                  <th scope="col">{t("catalogue.column.status").text}</th>
                </tr>
              </thead>

              <tbody>
                {skus.data.map((sku) => (
                  <tr key={sku.skuId}>
                    <td className="catalogue-thumbnail-cell">
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

                    <td>
                      <span className="catalogue-uom-badge">{sku.baseUomCode}</span>
                    </td>

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