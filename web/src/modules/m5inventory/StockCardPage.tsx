import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useInventoryApi } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { errorText } from "./stockView";

/**
 * The stock card of one item at one location (25A section 8, "Stock card"; doc 30 section 5.5):
 * every ledger movement of the item there, oldest first (opening balance, receipt, transfer in
 * and out, sale, dispatch, reversal), with the quantity after each. The running quantity comes
 * from the server; the screen adds nothing up. Reached from the stock position.
 */
export function StockCardPage() {
  const t = useT();
  const api = useInventoryApi();
  const formatInstant = useFormatInstant();
  const { locationId = "", skuId = "" } = useParams();

  const card = useQuery({
    queryKey: ["inventory", "stock-card", locationId, skuId],
    queryFn: () => api.stockCard(locationId, skuId)
  });

  return (
    <main className="shell-page">
      <p>
        <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      </p>
      <h1>{t("inventory.card.title").text}</h1>
      <p>
        <SkuLabel skuId={skuId} />
      </p>

      {card.isLoading && <p>{t("inventory.loading").text}</p>}
      {card.isError && <p role="alert">{errorText(card.error, t("inventory.error.generic").text)}</p>}
      {card.data?.length === 0 && <p>{t("inventory.card.empty").text}</p>}
      {card.data && card.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("inventory.card.column.when").text}</th>
              <th>{t("inventory.card.column.movement").text}</th>
              <th>{t("inventory.column.condition").text}</th>
              <th>{t("inventory.card.column.qty").text}</th>
              <th>{t("inventory.card.column.balance").text}</th>
              <th>{t("inventory.column.unit_cost").text}</th>
            </tr>
          </thead>
          <tbody>
            {card.data.map(({ movement, balanceAfter }) => (
              <tr key={movement.movementId}>
                <td>{movement.occurredAt ? formatInstant(movement.occurredAt) : ""}</td>
                <td>{t(`inventory.movement.${movement.movementType}`).text}</td>
                <td>{t(`inventory.condition.${movement.condition}`).text}</td>
                <td>{movement.qtyDelta > 0 ? `+${movement.qtyDelta}` : movement.qtyDelta}</td>
                <td>{balanceAfter}</td>
                <td>{movement.unitCostAtMovement == null ? "" : <MoneyDisplay amount={movement.unitCostAtMovement} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
