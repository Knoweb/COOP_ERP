import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useTradingApi } from "./tradingApi";

// The names a trading document shows, each read from the module that owns it. A party, place or
// item the caller's scope may not read (a counterparty's warehouse, say) shows its short id: the
// document is still readable, only the name is missing.

export function shortId(id: string): string {
  return id.slice(-6);
}

export function inLocale(locale: string, en: string, si?: string | null, ta?: string | null): string {
  return (locale === "si" ? si : locale === "ta" ? ta : null) || en;
}

/** The item's code and name as text, from the catalogue (M2). */
export function useSkuName(skuId: string): string {
  const api = useTradingApi();
  const { locale } = useIntl();
  const sku = useQuery({ queryKey: ["trading", "sku", skuId], queryFn: () => api.sku(skuId), staleTime: Infinity, retry: false });
  return sku.data ? `${sku.data.skuCode} ${inLocale(locale, sku.data.nameEn, sku.data.nameSi, sku.data.nameTa)}` : shortId(skuId);
}

/** A trading party's code and legal name as text, from the party register (M1). */
export function useEntityName(entityId: string): string {
  const api = useTradingApi();
  const { locale } = useIntl();
  const entity = useQuery({ queryKey: ["trading", "entity", entityId], queryFn: () => api.entity(entityId), staleTime: Infinity, retry: false });
  return entity.data
    ? `${entity.data.entityCode} ${inLocale(locale, entity.data.legalNameEn, entity.data.legalNameSi, entity.data.legalNameTa)}`
    : shortId(entityId);
}

/** A location's code and name as text, from the party register (M1). */
export function useLocationName(locationId: string): string {
  const api = useTradingApi();
  const { locale } = useIntl();
  const location = useQuery({
    queryKey: ["trading", "location", locationId],
    queryFn: () => api.location(locationId),
    staleTime: Infinity,
    retry: false
  });
  return location.data
    ? `${location.data.locationCode} ${inLocale(locale, location.data.nameEn, location.data.nameSi, location.data.nameTa)}`
    : shortId(locationId);
}

export function SkuLabel({ skuId }: { skuId: string }) {
  return <span>{useSkuName(skuId)}</span>;
}

export function EntityName({ entityId }: { entityId: string }) {
  return <span>{useEntityName(entityId)}</span>;
}

export function LocationName({ locationId }: { locationId: string }) {
  return <span>{useLocationName(locationId)}</span>;
}

/** An option of a select naming a trading party: an option holds text only. */
export function EntityOption({ value, entityId }: { value: string; entityId: string }) {
  return <option value={value}>{useEntityName(entityId)}</option>;
}
