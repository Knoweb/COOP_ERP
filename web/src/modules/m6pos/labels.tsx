import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { skuText } from "../../shell/i18n/localName";
import { usePosApi } from "./posApi";

/** "Till 1", or a dash when the till position is not known. */
export function TillLabel({ number }: { number: number | null }) {
  const t = useT();
  return <span>{number === null ? "—" : t("pos.till", undefined, { number }).text}</span>;
}

/** One tender kind in the reader's language; a kind this screen does not know shows as sent. */
export function useTenderText() {
  const t = useT();
  return (kind: string) => {
    const message = t(`pos.tender.${kind}`);
    return message.isFallback ? kind : message.text;
  };
}

export function TenderKinds({ kinds }: { kinds: string[] }) {
  const tenderText = useTenderText();
  return <span>{kinds.map(tenderText).join(", ")}</span>;
}

/** The item's code and name, read from the catalogue (M2); the id while it loads. */
export function SkuLabel({ skuId }: { skuId?: string }) {
  const api = usePosApi();
  const { locale } = useIntl();
  const sku = useQuery({
    queryKey: ["pos", "sku", skuId],
    queryFn: () => api.getSku(skuId!),
    enabled: skuId !== undefined,
    staleTime: Infinity
  });
  return <span>{sku.data ? skuText(sku.data, locale) : (skuId ?? "")}</span>;
}
