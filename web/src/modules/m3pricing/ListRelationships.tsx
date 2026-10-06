import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { entityText } from "../../shell/i18n/localName";
import { StateChip } from "../../shell/components/StateChip";
import type { ChipState } from "../../shell/components/StateChip";
import { usePricingApi, type PriceList, type Relationship } from "./pricingApi";

const STATUS_LOOK: Record<Relationship["status"], ChipState> = {
  DRAFT: "draft",
  ACTIVE: "issued",
  SUSPENDED: "alert",
  REPLACED: "void"
};

/**
 * The buyers a trade price list applies to (docs/demo/02-pricing.md step 3): the owner's trading
 * relationships (M1) whose terms name this list. Read only; the terms are changed on the
 * relationship's own sheet in the party module. A replaced row is history and is left out. A caller
 * who may not read relationships sees nothing here rather than an error: the list is still usable.
 */
export function ListRelationships({ list }: { list: PriceList }) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const relationships = useQuery({
    queryKey: ["pricing", "sellerRelationships"],
    queryFn: () => api.sellerRelationships(),
    retry: false
  });
  if (!relationships.isSuccess) {
    return null;
  }
  const onThisList = relationships.data.filter(
    (row) =>
      row.status !== "REPLACED" &&
      (!list.ownerEntityId || row.sellerEntityId === list.ownerEntityId) &&
      (row.priceListId === list.priceListId || row.priceListId === list.rootPriceListId)
  );

  return (
    <section className="pricing-section" aria-labelledby="pricing-list-relationships">
      <h2 id="pricing-list-relationships">{t("pricing.relationships.title").text}</h2>
      {onThisList.length === 0 ? (
        <p>{t("pricing.relationships.empty").text}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{t("pricing.relationships.buyer").text}</th>
              <th>{t("pricing.relationships.status").text}</th>
              <th>{t("pricing.relationships.from").text}</th>
            </tr>
          </thead>
          <tbody>
            {onThisList.map((row) => (
              <tr key={row.relationshipId}>
                <td>
                  <BuyerName entityId={row.buyerEntityId} />
                </td>
                <td>
                  <StateChip state={STATUS_LOOK[row.status]} label={t(`pricing.relationships.status.${row.status}`).text} />
                </td>
                <td>{formatDate(row.effectiveFrom)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function BuyerName({ entityId }: { entityId: string }) {
  const api = usePricingApi();
  const { locale } = useIntl();
  const entity = useQuery({
    queryKey: ["pricing", "entity", entityId],
    queryFn: () => api.entity(entityId),
    staleTime: Infinity,
    retry: false
  });
  return <>{entity.data ? entityText(entity.data, locale) : entityId.slice(-6)}</>;
}
