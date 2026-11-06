import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { usePricingApi, type CustomerPriceLookup } from "./pricingApi";
import { PricingTabs } from "./PricingTabs";
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
      <h1>{t("pricing.customer.portal_title").text}</h1>
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.customer.portal_intro").text}</p>

      <form onSubmit={handleSearch} className="customer-price-search-bar" role="search">
        <label className="modern-field customer-shop-select">
          <span className="modern-field__label">{t("pricing.customer.shop_label").text}</span>
          <select
            value={selectedLocationId}
            onChange={(e) => handleLocationChange(e.target.value)}
            disabled={locationsQuery.isLoading}
            aria-label={t("pricing.customer.shop_label").text}
          >
            {locations.length === 0 && (
              <option value="">{t("pricing.customer.choose_shop").text}</option>
            )}
            {locations.map((loc) => (
              <option key={loc.locationId} value={loc.locationId}>
                {loc.nameEn || loc.locationCode}
              </option>
            ))}
          </select>
        </label>

        <label className="modern-field customer-query-input">
          <span className="modern-field__label">{t("pricing.find").text}</span>
          <input
            type="search"
            value={searchQuery}
            placeholder={t("pricing.customer.search_placeholder").text}
            onChange={(e) => setSearchQuery(e.target.value)}
            aria-label={t("pricing.customer.search_placeholder").text}
          />
        </label>

        <button
          type="submit"
          className="modern-btn modern-btn--primary customer-search-submit"
          disabled={isSearching || !selectedLocationId}
        >
          {isSearching ? t("pricing.list.loading").text : t("pricing.customer.search_button").text}
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
              <header className="customer-price-card__header">
                <h2 className="customer-price-card__title">{item.productName}</h2>
                <div className="customer-price-card__meta">
                  <span className="customer-price-chip sku-chip">{item.skuCode}</span>
                  <span className="customer-price-chip uom-chip">
                    {t("pricing.customer.per_unit", undefined, { unit: item.uom }).text}
                  </span>
                  <span
                    className={`customer-price-chip status-chip ${
                      item.isAvailable ? "status-chip--available" : "status-chip--unavailable"
                    }`}
                  >
                    {item.isAvailable
                      ? t("pricing.customer.available").text
                      : t("pricing.customer.unavailable").text}
                  </span>
                </div>
              </header>

              <div className="customer-price-card__body">
                <div className="customer-price-card__amount-row">
                  <span className="customer-price-card__price-label">
                    {t("pricing.customer.verified_price").text}
                  </span>
                  <strong className="customer-price-card__price-value">
                    {item.currency} {Number(item.publicPrice).toFixed(2)}
                  </strong>
                </div>

                {item.isPriceControlled && item.controlPrice != null && (
                  <div className="customer-price-card__control-notice">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
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
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <p>{t("pricing.customer.privacy_banner").text}</p>
        </div>
      </footer>
    </main>
  );
}
