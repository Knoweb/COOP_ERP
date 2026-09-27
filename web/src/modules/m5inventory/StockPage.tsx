import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { useInventoryApi } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { errorText } from "./stockView";

/**
 * The stock position of one location (doc 30 section 5.5; 25A section 8, "Stock position", demo
 * scope): every lot with stock by item, batch and condition, GOOD lots in FEFO order first, with
 * what can be sold or allocated of each item there. Cost shows only when the server sends it
 * (an owner's user). The way to load the opening stock of a location starts here.
 */
export function StockPage() {
  const t = useT();
  const api = useInventoryApi();
  const canPrepare = useHasPermission("inv.opening.prepare");
  const [locationId, setLocationId] = useState("");

  const balances = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });
  const skuIds = [...new Set((balances.data ?? []).map((lot) => lot.skuId))];
  const availability = useQuery({
    queryKey: ["inventory", "availability", locationId, skuIds.join(",")],
    queryFn: () => api.availability(locationId, skuIds),
    enabled: skuIds.length > 0
  });
  const availableOf = (skuId: string) => availability.data?.find((row) => row.skuId === skuId)?.available;

  return (
    <main className="shell-page">
      <h1>{t("inventory.title").text}</h1>
      <div style={{ display: "flex", gap: "var(--space-2)", alignItems: "end", marginBottom: "var(--space-3)" }}>
        <LocationPicker value={locationId} onChange={setLocationId} />
        {canPrepare && <Link to="/inventory/opening/new">{t("inventory.opening.new").text}</Link>}
        <Link to="/inventory/transfers">{t("inventory.transfer.link").text}</Link>
      </div>

      {balances.isLoading && <p>{t("inventory.loading").text}</p>}
      {balances.isError && <p role="alert">{errorText(balances.error, t("inventory.error.generic").text)}</p>}
      {balances.data?.length === 0 && <p>{t("inventory.balances.empty").text}</p>}
      {balances.data && balances.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("inventory.column.item").text}</th>
              <th>{t("inventory.column.batch").text}</th>
              <th>{t("inventory.column.expiry").text}</th>
              <th>{t("inventory.column.condition").text}</th>
              <th>{t("inventory.column.on_hand").text}</th>
              <th>{t("inventory.column.fefo").text}</th>
              <th>{t("inventory.column.available").text}</th>
              <th>{t("inventory.column.unit_cost").text}</th>
            </tr>
          </thead>
          <tbody>
            {balances.data.map((lot) => (
              <tr key={lot.stockLotId}>
                <td>
                  <SkuLabel skuId={lot.skuId} />
                </td>
                <td>{lot.batchNo ?? ""}</td>
                <td>{lot.expiryDate ?? ""}</td>
                <td>{t(`inventory.condition.${lot.condition}`).text}</td>
                <td>{lot.qtyOnHand}</td>
                <td>{lot.fefoRank ?? ""}</td>
                <td>{availableOf(lot.skuId) ?? ""}</td>
                <td>{lot.unitCost ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
