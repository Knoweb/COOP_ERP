import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useFormatDate } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import "./inventory.css";
import { useInventoryApi, type LotBalance, type Sku } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { StockRepriceModal } from "./StockRepriceModal";
import { errorText, isSyntheticBatchNo } from "./stockView";

export function StockPage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const canPrepare = useHasPermission("inv.opening.prepare");
  const canReprice = useHasPermission("inv.stock.reprice");
  const [locationId, setLocationId] = useState("");
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedLot, setSelectedLot] = useState<LotBalance | null>(null);

  const balances = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });

  const skuIds = useMemo(
    () => [...new Set((balances.data ?? []).map((lot) => lot.skuId))],
    [balances.data]
  );

  const skuQueries = useQueries({
    queries: skuIds.map((skuId) => ({
      queryKey: ["inventory", "sku", skuId],
      queryFn: () => api.getSku(skuId),
      staleTime: Infinity
    }))
  });

  const skuMap = useMemo(() => {
    const map = new Map<string, Sku>();
    for (const q of skuQueries) {
      if (q.data) {
        map.set(q.data.skuId, q.data);
      }
    }
    return map;
  }, [skuQueries]);

  const availability = useQuery({
    queryKey: ["inventory", "availability", locationId, skuIds.join(",")],
    queryFn: () => api.availability(locationId, skuIds),
    enabled: skuIds.length > 0
  });

  const availableOf = (skuId: string) =>
    availability.data?.find((row) => row.skuId === skuId)?.available;

  const filteredBalances = useMemo(() => {
    const data = balances.data ?? [];
    const q = searchQuery.trim().toLowerCase();
    if (!q) {
      return data;
    }

    return data.filter((lot) => {
      const sku = skuMap.get(lot.skuId);
      if (sku) {
        if (sku.skuCode && sku.skuCode.toLowerCase().includes(q)) return true;
        if (sku.nameEn && sku.nameEn.toLowerCase().includes(q)) return true;
        if (sku.nameSi && sku.nameSi.toLowerCase().includes(q)) return true;
        if (sku.nameTa && sku.nameTa.toLowerCase().includes(q)) return true;
      }
      if (lot.skuId && lot.skuId.toLowerCase().includes(q)) return true;
      if (lot.batchNo && lot.batchNo.toLowerCase().includes(q)) return true;
      if (lot.condition && lot.condition.toLowerCase().includes(q)) return true;
      return false;
    });
  }, [balances.data, searchQuery, skuMap]);

  return (
    <main className="shell-page">
      <Link className="back-link" to="/reporting">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back_dashboard").text}
      </Link>

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

      <div className="stock-controls-toolbar">
        <button
          type="button"
          className="filter-toggle"
          onClick={() => setFiltersOpen(!filtersOpen)}
          aria-expanded={filtersOpen}
          aria-controls="stock-filters"
        >
          <svg
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M4 7h16M7 12h10M10 17h4" />
          </svg>
          {t("inventory.filter.toggle").text}
          <span aria-hidden="true">{filtersOpen ? "▴" : "▾"}</span>
        </button>

        <div className="stock-search-wrap">
          <span className="modern-control modern-control--search stock-search-control">
            <svg
              className="modern-control__leading-icon"
              viewBox="0 0 24 24"
              aria-hidden="true"
              focusable="false"
            >
              <circle cx="11" cy="11" r="6" />
              <path d="m16 16 4 4" />
            </svg>
            <input
              type="search"
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder={t("inventory.stock.search_placeholder").text}
              aria-label={t("inventory.stock.search_placeholder").text}
              className="stock-search-input"
            />
            {searchQuery ? (
              <button
                type="button"
                className="stock-search-clear"
                onClick={() => setSearchQuery("")}
                aria-label={t("inventory.remove").text}
              >
                ✕
              </button>
            ) : null}
          </span>
        </div>
      </div>

      <section
        id="stock-filters"
        hidden={!filtersOpen}
        className="modern-filter-panel modern-filter-panel--stock stock-positions-filters"
      >
        <div className="modern-location-picker">
          <LocationPicker
            value={locationId}
            onChange={setLocationId}
          />
        </div>

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

          <Link className="action-link" to="/inventory/transfer-requests">
            <span>{t("inventory.request.link").text}</span>
          </Link>
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

      {balances.data && balances.data.length === 0 && (
        <p>{t("inventory.balances.empty").text}</p>
      )}

      {balances.data && balances.data.length > 0 && (
        <section className="modern-table-card stock-positions-card">
          <div className="modern-table-scroll stock-positions-scroll">
            <table className="modern-table stock-table stock-positions-table">
              <colgroup>
                <col className="col-item" />
                <col className="col-batch" />
                <col className="col-expiry" />
                <col className="col-condition" />
                <col className="col-on-hand" />
                <col className="col-fefo" />
                <col className="col-available" />
                <col className="col-unit-cost" />
                {canReprice && <col className="col-actions" />}
              </colgroup>
              <thead>
                <tr>
                  <th className="col-item">{t("inventory.column.item").text}</th>
                  <th className="col-batch">{t("inventory.column.batch").text}</th>
                  <th className="col-expiry">{t("inventory.column.expiry").text}</th>
                  <th className="col-condition">{t("inventory.column.condition").text}</th>
                  <th className="col-on-hand numeric-cell">{t("inventory.column.on_hand").text}</th>
                  <th className="col-fefo">{t("inventory.column.fefo").text}</th>
                  <th className="col-available numeric-cell">{t("inventory.column.available").text}</th>
                  <th className="col-unit-cost numeric-cell">{t("inventory.column.unit_cost").text}</th>
                  {canReprice && <th className="col-actions">{t("inventory.actions").text}</th>}
                </tr>
              </thead>

              <tbody>
                {filteredBalances.length === 0 ? (
                  <tr>
                    <td colSpan={canReprice ? 9 : 8} className="empty-table-cell">
                      {t("inventory.find.none").text}
                    </td>
                  </tr>
                ) : (
                  filteredBalances.map((lot) => (
                    <tr key={lot.stockLotId}>
                      <td className="col-item">
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

                      <td className="col-batch">
                        {isSyntheticBatchNo(lot.batchNo)
                          ? t("inventory.field.batch_not_tracked").text
                          : (lot.batchNo || "—")}
                      </td>

                      <td className="col-expiry">{lot.expiryDate ? formatDate(lot.expiryDate) : "—"}</td>

                      <td className="col-condition">
                        <span className="condition-chip">
                          {t(`inventory.condition.${lot.condition}`).text}
                        </span>
                      </td>

                      <td className="col-on-hand numeric-cell">
                        {lot.qtyOnHand}
                      </td>

                      <td className="col-fefo">
                        {lot.expired ? (
                          <span className="condition-chip">{t("inventory.lot.expired").text}</span>
                        ) : (
                          (lot.fefoRank ?? "—")
                        )}
                      </td>

                      <td className="col-available numeric-cell numeric-cell--strong">
                        {availableOf(lot.skuId) ?? "—"}
                      </td>

                      <td className="col-unit-cost numeric-cell">
                        {lot.unitCost != null ? <MoneyDisplay amount={lot.unitCost} /> : "—"}
                      </td>

                      {canReprice && (
                        <td className="col-actions">
                          <button
                            type="button"
                            className="button button--small button--secondary"
                            onClick={() => setSelectedLot(lot)}
                          >
                            {t("inventory.reprice.action").text}
                          </button>
                        </td>
                      )}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <StockRepriceModal
        isOpen={selectedLot !== null}
        onClose={() => setSelectedLot(null)}
        lot={selectedLot}
        sku={selectedLot ? skuMap.get(selectedLot.skuId) ?? null : null}
        onSuccess={() => {
          queryClient.invalidateQueries({ queryKey: ["inventory", "balances", locationId] });
        }}
      />
    </main>
  );
}