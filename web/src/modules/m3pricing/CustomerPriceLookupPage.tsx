import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { usePricingApi, type CustomerPriceLookup } from "./pricingApi";
import { PageHeader } from "../../shell/components/PageHeader";
import { PricingTabs } from "./PricingTabs";
import { PricingSelect } from "./PricingSelect";
import "./pricing.css";

/**
 * CustomerPriceLookupPage (M3-08): Public/Customer price lookup portal for cooperative shops.
 * Displays legally permitted retail selling prices resolved via the core pricing engine.
 * Internal supplier costs, commercial margins, batch details, and restricted owner data
 * are strictly never displayed or returned.
 */
export function CustomerPriceLookupPage() {
  const t = useT();
  const api = usePricingApi();

  const [selectedLocationId, setSelectedLocationId] = useState<string>("");
  const [searchQuery, setSearchQuery] = useState<string>("");
  const [results, setResults] = useState<CustomerPriceLookup[]>([]);
  const [isSearching, setIsSearching] = useState<boolean>(false);
  const [searched, setSearched] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const locationsQuery = useQuery({
    queryKey: ["pricing", "locations"],
    queryFn: () => api.locations()
  });

  const locations = useMemo(() => locationsQuery.data ?? [], [locationsQuery.data]);

  useEffect(() => {
    if (!selectedLocationId && locations.length > 0) {
      setSelectedLocationId(locations[0].locationId);
    }
  }, [locations, selectedLocationId]);

  const runSearch = async (locId: string, q: string) => {
    if (!locId) {
      setError(t("pricing.customer.choose_shop").text);
      return;
    }
    setError(null);
    setIsSearching(true);
    setSearched(true);
    try {
      // First attempt search across items at location
      const items = await api.searchCustomerPrices(locId, q.trim());
      if (items.length > 0) {
        setResults(items);
      } else if (q.trim()) {
        // If search returned empty, try direct lookup by barcode or code
        const direct = await api.lookupCustomerPrice(locId, {
          barcode: q.trim(),
          skuCode: q.trim(),
          query: q.trim()
        });
        if (direct) {
          setResults([direct]);
        } else {
          setResults([]);
        }
      } else {
        setResults([]);
      }
    } catch {
      setError(t("pricing.error.generic").text);
      setResults([]);
    } finally {
      setIsSearching(false);
    }
  };

  const handleSearch = (e: FormEvent) => {
    e.preventDefault();
    void runSearch(selectedLocationId, searchQuery);
  };

  const handleLocationChange = (newLocId: string) => {
    setSelectedLocationId(newLocId);
    if (searched) {
      void runSearch(newLocId, searchQuery);
    }
  };

  return (
    <main className="shell-page customer-price-portal">
      <PageHeader icon="customer-lookup" title={t("pricing.customer.portal_title").text} />
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.customer.portal_intro").text}</p>

      <form onSubmit={handleSearch} className="customer-price-search-bar" role="search">
        <label className="modern-field customer-shop-select">
          <span className="modern-field__label pricing-filter-label">{t("pricing.customer.shop_label").text}</span>
          <PricingSelect
            aria-label={t("pricing.customer.shop_label").text}
            value={selectedLocationId}
            onChange={handleLocationChange}
            disabled={locationsQuery.isLoading}
            options={locations.map((loc) => ({
              value: loc.locationId,
              label: loc.nameEn || loc.locationCode
            }))}
          />
        </label>

        <label className="modern-field customer-query-input">
          <span className="modern-field__label pricing-filter-label">{t("pricing.find").text}</span>
          <div className="customer-price-search-wrapper">
            <svg
              className="customer-price-search-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="11" cy="11" r="8" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
            <input
              type="search"
              className="customer-price-search-input"
              value={searchQuery}
              placeholder={t("pricing.customer.search_placeholder").text}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  setSearchQuery("");
                }
              }}
              aria-label={t("pricing.customer.search_placeholder").text}
            />
            {searchQuery && (
              <button
                type="button"
                className="customer-price-search-clear"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setSearchQuery("");
                }}
                aria-label={t("pricing.search.clear").text}
                title={t("pricing.search.clear").text}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            )}
          </div>
        </label>

        <button
          type="submit"
          className="btn btn--primary customer-search-submit"
          disabled={isSearching || !selectedLocationId}
        >
          <svg
            className={`customer-search-btn-icon ${isSearching ? "customer-search-spin" : ""}`}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            {isSearching ? (
              <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
            ) : (
              <>
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </>
            )}
          </svg>
          <span>
            {isSearching ? t("pricing.list.loading").text : t("pricing.customer.search_button").text}
          </span>
        </button>
      </form>

      {error && (
        <p className="customer-error-banner" role="alert">
          {error}
        </p>
      )}

      {isSearching && (
        <div className="customer-loading-indicator" role="status">
          <p>{t("pricing.list.loading").text}</p>
        </div>
      )}

      {!isSearching && searched && results.length === 0 && (
        <div className="customer-empty-results" role="status">
          <p>{t("pricing.customer.no_results").text}</p>
        </div>
      )}

      {!isSearching && results.length > 0 && (
        <div className="customer-price-grid" data-testid="customer-price-results">
          {results.map((item) => (
            <article key={item.skuId} className="customer-price-card">
              <div className="customer-price-card__header">
                <div className="customer-price-card__top">
                  <div className="customer-price-card__icon-badge" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                      <path d="M4 5h9l7 7-8 8-8-8V5Z" />
                      <circle cx="9" cy="10" r="1.5" />
                    </svg>
                  </div>
                  <span
                    className={`customer-price-chip status-chip ${
                      item.isAvailable ? "status-chip--available" : "status-chip--unavailable"
                    }`}
                  >
                    <span className="status-chip__dot" aria-hidden="true" />
                    {item.isAvailable
                      ? t("pricing.customer.available").text
                      : t("pricing.customer.unavailable").text}
                  </span>
                </div>

                <h2 className="customer-price-card__title">{item.productName}</h2>

                <div className="customer-price-card__meta">
                  <span className="customer-price-chip sku-chip">
                    <svg viewBox="0 0 24 24" className="chip-icon" fill="none" stroke="currentColor" aria-hidden="true">
                      <path d="M4 6v12M7 6v12M11 6v12M14 6v12M17 6v12M20 6v12" />
                    </svg>
                    <span>{item.skuCode}</span>
                  </span>
                  <span className="customer-price-chip uom-chip">
                    <svg viewBox="0 0 24 24" className="chip-icon" fill="none" stroke="currentColor" aria-hidden="true">
                      <path d="m7.5 4.27 9 5.15M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
                      <path d="m3.3 7 8.7 5 8.7-5M12 22V12" />
                    </svg>
                    <span>{t("pricing.customer.per_unit", undefined, { unit: item.uom }).text}</span>
                  </span>
                </div>
              </div>

              <div className="customer-price-card__body">
                <div className="customer-price-card__amount-row">
                  <span className="customer-price-card__price-label">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="8 12 11 15 16 9" />
                    </svg>
                    {t("pricing.customer.verified_price").text}
                  </span>
                  <strong className="customer-price-card__price-value">
                    {item.currency} {Number(item.publicPrice).toFixed(2)}
                  </strong>
                </div>

                {item.isPriceControlled && item.controlPrice != null && (
                  <div className="customer-price-card__control-notice">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
                      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                    </svg>
                    <span>
                      {t("pricing.customer.control_price_notice", undefined, {
                        price: `${item.currency} ${Number(item.controlPrice).toFixed(2)}`
                      }).text}
                    </span>
                  </div>
                )}
              </div>
            </article>
          ))}
        </div>
      )}

      <footer className="customer-privacy-footer" role="contentinfo">
        <div className="customer-privacy-footer__content">
          <div className="customer-privacy-footer__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
          <p>{t("pricing.customer.privacy_banner").text}</p>
        </div>
      </footer>
    </main>
  );
}
