import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { skuText } from "../../shell/i18n/localName";
import { useInventoryApi } from "./inventoryApi";

/** The item's code and name, read from the catalogue (M2); the id while it loads. */
export function SkuLabel({ skuId }: { skuId: string }) {
  const api = useInventoryApi();
  const { locale } = useIntl();
  const sku = useQuery({ queryKey: ["inventory", "sku", skuId], queryFn: () => api.getSku(skuId), staleTime: Infinity });
  return <span>{sku.data ? skuText(sku.data, locale) : skuId}</span>;
}
