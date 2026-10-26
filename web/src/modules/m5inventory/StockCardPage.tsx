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
      <Link className="back-link" to="/inventory">
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          focusable="false"
        >
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        <span>{t("inventory.back").text}</span>
      </Link>

      <header className="stock-card-header">
        <h1>{t("inventory.card.title").text}</h1>
        <div className="stock-card-sku">
          <SkuLabel skuId={skuId} />
        </div>
      </header>

      {card.isLoading && <p>{t("inventory.loading").text}</p>}
      {card.isError && <p role="alert">{errorText(card.error, t("inventory.error.generic").text)}</p>}
      {card.data && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table stock-table stock-card-table">
              <colgroup>
                <col className="col-when" />
                <col className="col-movement" />
                <col className="col-condition" />
                <col className="col-qty" />
                <col className="col-balance" />
                <col className="col-unit-cost" />
              </colgroup>
              <thead>
                <tr>
                  <th>{t("inventory.card.column.when").text}</th>
                  <th>{t("inventory.card.column.movement").text}</th>
                  <th>{t("inventory.column.condition").text}</th>
                  <th className="numeric-cell">{t("inventory.card.column.qty").text}</th>
                  <th className="numeric-cell">{t("inventory.card.column.balance").text}</th>
                  <th className="numeric-cell">{t("inventory.column.unit_cost").text}</th>
                </tr>
              </thead>
              <tbody>
                {card.data.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="empty-table-cell">
                      {t("inventory.card.empty").text}
                    </td>
                  </tr>
                ) : (
                  card.data.map(({ movement, balanceAfter }) => (
                    <tr key={movement.movementId}>
                      <td className="cell-when">{movement.occurredAt ? formatInstant(movement.occurredAt) : ""}</td>
                      <td>
                        <span className="movement-chip">{t(`inventory.movement.${movement.movementType}`).text}</span>
                      </td>
                      <td>
                        <span className="condition-chip">
                          {t(`inventory.condition.${movement.condition}`).text}
                        </span>
                      </td>
                      <td className={`numeric-cell ${movement.qtyDelta > 0 ? "qty--positive" : movement.qtyDelta < 0 ? "qty--negative" : ""}`}>
                        {movement.qtyDelta > 0 ? `+${movement.qtyDelta}` : movement.qtyDelta}
                      </td>
                      <td className="numeric-cell numeric-cell--strong">{balanceAfter}</td>
                      <td className="numeric-cell">
                        {movement.unitCostAtMovement == null ? "" : <MoneyDisplay amount={movement.unitCostAtMovement} />}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}

