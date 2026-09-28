import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { useInventoryApi } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { errorText, isSyntheticBatchNo } from "./stockView";

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

  const skuIds = [
    ...new Set((balances.data ?? []).map((lot) => lot.skuId))
  ];

  const availability = useQuery({
    queryKey: ["inventory", "availability", locationId, skuIds.join(",")],
    queryFn: () => api.availability(locationId, skuIds),
    enabled: skuIds.length > 0
  });

  const availableOf = (skuId: string) =>
    availability.data?.find((row) => row.skuId === skuId)?.available;

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("inventory.title").text}
        actions={
          <>
            {canPrepare ? (
              <Link
                className="action-link"
                to="/inventory/opening/new"
              >
                <span className="action-link__symbol" aria-hidden="true">
                  +
                </span>

                <span>{t("inventory.opening.new").text}</span>
              </Link>
            ) : null}

            <Link
              className="action-link action-link--primary"
              to="/inventory/transfers"
            >
              <span className="action-link__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M3 7h11v9H3V7Z" />
                  <path d="M14 10h4l3 3v3h-7v-6Z" />
                  <circle cx="7" cy="18" r="2" />
                  <circle cx="18" cy="18" r="2" />
                </svg>
              </span>

              <span>{t("inventory.transfer.link").text}</span>
            </Link>

          </>
        }
      />

      {/* Stock control, below the header: five links in the header's action column squeezed the
          title to nothing in Sinhala and Tamil. */}
      <div className="inventory-control-links">
        <Link className="action-link" to="/inventory/counts">
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/repacks">
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
      </div>

      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker
            value={locationId}
            onChange={setLocationId}
          />
        </div>
      </section>

      {balances.isLoading && <p>{t("inventory.loading").text}</p>}

      {balances.isError && (
        <p role="alert">
          {errorText(
            balances.error,
            t("inventory.error.generic").text
          )}
        </p>
      )}

      {balances.data?.length === 0 && (
        <p>{t("inventory.balances.empty").text}</p>
      )}

      {balances.data && balances.data.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
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
                      <Link
                        className="entity-link"
                        to={`/inventory/locations/${locationId}/skus/${lot.skuId}`}
                      >
                        <span className="entity-link__icon" aria-hidden="true">
                          <svg viewBox="0 0 24 24">
                            <path d="M4 7 12 3l8 4-8 4-8-4Z" />
                            <path d="M4 7v10l8 4 8-4V7" />
                          </svg>
                        </span>

                        <SkuLabel skuId={lot.skuId} />
                      </Link>
                    </td>

                    <td>
                      {isSyntheticBatchNo(lot.batchNo)
                        ? t("inventory.field.batch_not_tracked").text
                        : (lot.batchNo ?? "")}
                    </td>

                    <td>{lot.expiryDate ?? ""}</td>

                    <td>
                      <span className="condition-chip">
                        {t(`inventory.condition.${lot.condition}`).text}
                      </span>
                    </td>

                    <td className="numeric-cell">
                      {lot.qtyOnHand}
                    </td>

                    <td className="numeric-cell">
                      {lot.fefoRank ?? ""}
                    </td>

                    <td className="numeric-cell numeric-cell--strong">
                      {availableOf(lot.skuId) ?? ""}
                    </td>

                    <td className="numeric-cell">
                      {lot.unitCost ?? ""}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}