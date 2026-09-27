import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { entityText, inLocale, locationText, skuText } from "../../shell/i18n/localName";
import { useTradingApi, type DeliveryPoint, type Order } from "./tradingApi";

// The names a trading document shows, each read from the module that owns it. A party, place or
// item the caller's scope may not read (a counterparty's warehouse, say) shows its short id: the
// document is still readable, only the name is missing.

export function shortId(id: string): string {
  return id.slice(-6);
}

export { entityText, inLocale, locationText, skuText };

/** The item's code and name as text, from the catalogue (M2). */
export function useSkuName(skuId: string): string {
  const api = useTradingApi();
  const { locale } = useIntl();
  const sku = useQuery({ queryKey: ["trading", "sku", skuId], queryFn: () => api.sku(skuId), staleTime: Infinity, retry: false });
  return sku.data ? skuText(sku.data, locale) : shortId(skuId);
}

/** A trading party's code and legal name as text, from the party register (M1). */
export function useEntityName(entityId: string): string {
  const api = useTradingApi();
  const { locale } = useIntl();
  const entity = useQuery({ queryKey: ["trading", "entity", entityId], queryFn: () => api.entity(entityId), staleTime: Infinity, retry: false });
  return entity.data ? entityText(entity.data, locale) : shortId(entityId);
}

/** Where the location's name comes from; see useLocationName. */
export type LocationSource = {
  /**
   * False when the location is the counterparty's: M1 lets only the owner read its locations
   * (CR-24A-2), so the name is not asked for, which would only be refused (404).
   */
  own?: boolean;
  /** The delivery point an order names (its snapshot, V0004), when the page already has it. */
  known?: DeliveryPoint | null;
  /** An order whose delivery point this location is, read for its snapshot when not `own`. */
  orderId?: string | null;
};

/**
 * A location's code and name as text. The caller's own location is read from the party register
 * (M1). A counterparty's is never asked for there: it is named from the order's snapshot of its
 * delivery point, when this is the order's delivery location, and otherwise shows a dash.
 */
export function useLocationName(locationId: string, source: LocationSource = {}): string {
  const api = useTradingApi();
  const { locale } = useIntl();
  const own = source.own ?? true;
  const needsOrder = !own && !source.known && !!source.orderId;
  const location = useQuery({
    queryKey: ["trading", "location", locationId],
    queryFn: () => api.location(locationId),
    enabled: own && !source.known,
    staleTime: Infinity,
    retry: false
  });
  const order = useQuery({
    queryKey: ["trading", "order", source.orderId],
    queryFn: () => api.order(source.orderId!),
    enabled: needsOrder,
    retry: false
  });
  const point = deliveryPointOf(locationId, source.known ?? null, order.data ?? null);
  if (point) {
    return locationText({ locationCode: point.code, ...point }, locale);
  }
  if (location.data) {
    return locationText(location.data, locale);
  }
  return own ? shortId(locationId) : "—";
}

/**
 * The snapshot that names the location: the one given, else the order's when the order's
 * delivery location is this one. A plain function, tested on its own.
 */
export function deliveryPointOf(
  locationId: string,
  known: DeliveryPoint | null,
  order: Pick<Order, "deliverToLocationId" | "deliverTo"> | null
): DeliveryPoint | null {
  if (known) {
    return known;
  }
  if (order?.deliverTo && order.deliverToLocationId === locationId) {
    return order.deliverTo;
  }
  return null;
}

export function SkuLabel({ skuId }: { skuId: string }) {
  return <span>{useSkuName(skuId)}</span>;
}

export function EntityName({ entityId }: { entityId: string }) {
  return <span>{useEntityName(entityId)}</span>;
}

export function LocationName({ locationId, ...source }: { locationId: string } & LocationSource) {
  return <span>{useLocationName(locationId, source)}</span>;
}

/** An option of a select naming a trading party: an option holds text only. */
export function EntityOption({ value, entityId }: { value: string; entityId: string }) {
  return <option value={value}>{useEntityName(entityId)}</option>;
}
