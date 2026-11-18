import { useState } from "react";
import { businessToday } from "../../shell/i18n/formats";
import type { FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale, skuText } from "../../shell/i18n/localName";
import { PricingSelect } from "./PricingSelect";
import { usePricingApi } from "./pricingApi";
import type { Sku } from "./pricingApi";

/** The item's code and name, read from the catalogue (M2); the id while it loads. */
export function SkuName({ skuId }: { skuId: string }) {
  const api = usePricingApi();
  const { locale } = useIntl();
  const sku = useQuery({ queryKey: ["pricing", "sku", skuId], queryFn: () => api.getSku(skuId), staleTime: Infinity });
  return <span>{sku.data ? skuText(sku.data, locale) : skuId}</span>;
}

/** Finds catalogue items by code or name and hands the one chosen to the screen. */
export function SkuPicker({ onPick }: { onPick: (sku: Sku) => void }) {
  const t = useT();
  const { locale } = useIntl();
  const api = usePricingApi();
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Sku[] | null>(null);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setFound(await api.searchSkus(q.trim()));
  };

  const dismiss = (skuId: string) => {
    setFound((current) => {
      if (!current) return null;
      const next = current.filter((item) => item.skuId !== skuId);
      return next.length > 0 ? next : null;
    });
  };

  return (
    <div className="pricing-card pricing-sku-picker-card">
      <form onSubmit={search} className="pricing-search-form">
        <label className="modern-field" htmlFor="pricing-sku-search-input">
          <span className="modern-field__label pricing-filter-label">{t("pricing.field.find_item").text}</span>
        </label>
        <div className="pricing-search-input-group">
          <input
            id="pricing-sku-search-input"
            type="search"
            aria-label={t("pricing.field.find_item").text}
            className="pricing-input pricing-search-input"
            value={q}
            onChange={(event) => setQ(event.target.value)}
          />
          <button type="submit" className="modern-btn" disabled={!q.trim()}>
            {t("pricing.find").text}
          </button>
        </div>
        {found?.length === 0 && <p className="pricing-muted">{t("pricing.find.none").text}</p>}
        {found && found.length > 0 && (
          <ul className="pricing-sku-results">
            {found.map((sku) => {
              const itemName = inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa);
              return (
                <li key={sku.skuId} className="pricing-sku-result-item">
                  <button
                    type="button"
                    className="pricing-sku-add-btn"
                    aria-label={t("pricing.add_item", undefined, { code: sku.skuCode, name: itemName }).text}
                    onClick={() => onPick(sku)}
                  >
                    <span className="pricing-sku-add-btn__info">
                      <span className="pricing-sku-badge">{sku.skuCode}</span>
                      <span className="pricing-sku-add-btn__name">{itemName}</span>
                    </span>
                  </button>
                  <button
                    type="button"
                    className="pricing-sku-dismiss-btn"
                    aria-label={t("pricing.remove").text}
                    onClick={() => dismiss(sku.skuId)}
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
                </li>
              );
            })}
          </ul>
        )}
      </form>
    </div>
  );
}

/**
 * The selectable pricing units of a SKU. M2 owns the conversions;
 * M3 still validates the selected unit again when saving and publishing.
 */
export function SkuUomPicker({
  skuId,
  uomCode,
  onChange
}: {
  skuId: string;
  uomCode: string;
  onChange: (code: string) => void;
}) {
  const api = usePricingApi();
  const t = useT();
  const sku = useQuery({
    queryKey: ["pricing", "sku", skuId],
    queryFn: () => api.getSku(skuId),
    staleTime: Infinity
  });
  const conversions = useQuery({
    queryKey: ["pricing", "skuConversions", skuId],
    queryFn: () => api.skuConversions(skuId)
  });

  const today = businessToday();
  const choices = new Set<string>();

  if (sku.data?.baseUomCode) {
    choices.add(sku.data.baseUomCode);
  }

  for (const conversion of conversions.data ?? []) {
    if (
      conversion.effectiveFrom <= today &&
      (!conversion.effectiveTo || conversion.effectiveTo >= today)
    ) {
      choices.add(conversion.uomCode);
    }
  }

  // Keep an existing unit visible even when its conversion has expired.
  // Saving or publishing will then return the backend's UOM refusal.
  choices.add(uomCode);

  return (
    <PricingSelect
      portal
      compact
      ariaLabel={t("pricing.column.unit").text}
      value={uomCode}
      onChange={onChange}
      options={[...choices].map((code) => ({
        value: code,
        label: code
      }))}
    />
  );
}
