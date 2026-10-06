import { useState } from "react";
import type { FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale, skuText } from "../../shell/i18n/localName";
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

  return (
    <form onSubmit={search} className="pricing-search-form">
      <label className="pricing-form-field">
        {t("pricing.field.find_item").text}
        <input type="search" value={q} onChange={(event) => setQ(event.target.value)} />
      </label>
      <div>
        <button type="submit" disabled={!q.trim()}>
          {t("pricing.find").text}
        </button>
      </div>
      {found?.length === 0 && <p>{t("pricing.find.none").text}</p>}
      {found && found.length > 0 && (
        <ul className="pricing-list">
          {found.map((sku) => (
            <li key={sku.skuId}>
              <button type="button" onClick={() => onPick(sku)}>
                {t("pricing.add_item", undefined, { code: sku.skuCode, name: inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa) }).text}
              </button>
            </li>
          ))}
        </ul>
      )}
    </form>
  );
}
