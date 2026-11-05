import React, { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useFormatDate } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import "./inventory.css";
import {
  useInventoryApi,
  type LotBalance,
  type Sku,
  type StockSummary,
  type DepartmentCategory,
  type Uom
} from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { StockRepriceModal } from "./StockRepriceModal";
import { StockExplanationModal } from "./StockExplanationModal";
import { errorText, isSyntheticBatchNo } from "./stockView";

type StatusFilter = "all" | "in_stock" | "out_of_stock" | "reserved" | "in_transit" | "discrepant";

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
  const [departmentFilter, setDepartmentFilter] = useState("");
  const [uomFilter, setUomFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [expandedSkuIds, setExpandedSkuIds] = useState<Set<string>>(new Set());

  const [selectedLot, setSelectedLot] = useState<LotBalance | null>(null);
  const [explainingSku, setExplainingSku] = useState<{
    skuId: string;
    skuCode?: string;
    skuName?: string;
  } | null>(null);

  const departmentsQuery = useQuery({
    queryKey: ["catalogue", "departments"],
    queryFn: () => api.departments(),
    staleTime: 5 * 60 * 1000
  });

  const uomsQuery = useQuery({
    queryKey: ["catalogue", "uoms"],
    queryFn: () => api.uoms(),
    staleTime: 5 * 60 * 1000
  });

  const summariesQuery = useQuery({
    queryKey: [
      "inventory",
      "stock-summaries",
      locationId,
      departmentFilter,
      uomFilter
    ],
    queryFn: () =>
      api.stockSummaries(locationId, {
        departmentId: departmentFilter || undefined,
        uomCode: uomFilter || undefined,
        includeZero: true
      }),
    enabled: locationId !== ""
  });

  const balancesQuery = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });

  const lotsBySkuId = useMemo(() => {
    const map = new Map<string, LotBalance[]>();
    for (const lot of balancesQuery.data ?? []) {
      const existing = map.get(lot.skuId) ?? [];
      existing.push(lot);
      map.set(lot.skuId, existing);
    }
    return map;
  }, [balancesQuery.data]);

  const summaries = useMemo(() => summariesQuery.data ?? [], [summariesQuery.data]);

  const kpis = useMemo(() => {
    const totalItems = summaries.length;
    let inStockCount = 0;
    let reservedCount = 0;
    let inTransitCount = 0;
    let discrepancies = 0;

    const uomSet = new Set<string>();
    let totalOnHand = 0;
    let totalReserved = 0;
    let totalIncoming = 0;
    let totalOutgoing = 0;

    for (const s of summaries) {
      if (s.baseUomCode) {
        uomSet.add(s.baseUomCode.trim().toUpperCase());
      }
      if (s.qtyOnHand > 0) inStockCount++;
      if (s.reservedQty > 0) reservedCount++;
      if (s.inTransitIncomingQty > 0 || s.inTransitOutgoingQty > 0) inTransitCount++;
      if (
        (s.countedVarianceQty != null && s.countedVarianceQty !== 0) ||
        s.latestCountStatus === "DISCREPANT"
      ) {
        discrepancies++;
      }
      totalOnHand += s.qtyOnHand;
      totalReserved += s.reservedQty;
      totalIncoming += s.inTransitIncomingQty;
      totalOutgoing += s.inTransitOutgoingQty;
    }

    const isSingleUom = uomSet.size === 1;
    const singleUom = isSingleUom ? Array.from(uomSet)[0] : null;

    return {
      totalItems,
      inStockCount,
      reservedCount,
      inTransitCount,
      discrepancies,
      isSingleUom,
      singleUom,
      onHand: Number(totalOnHand.toFixed(3)),
      reserved: Number(totalReserved.toFixed(3)),
      inTransitIncoming: Number(totalIncoming.toFixed(3)),
      inTransitOutgoing: Number(totalOutgoing.toFixed(3))
    };
  }, [summaries]);

  const filteredSummaries = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();

    return summaries.filter((item: StockSummary) => {
      if (statusFilter === "in_stock" && item.qtyOnHand <= 0) return false;
      if (statusFilter === "out_of_stock" && item.qtyOnHand > 0) return false;
      if (statusFilter === "reserved" && item.reservedQty <= 0) return false;
      if (
        statusFilter === "in_transit" &&
        item.inTransitIncomingQty <= 0 &&
        item.inTransitOutgoingQty <= 0
      ) {
        return false;
      }
      if (statusFilter === "discrepant") {
        const hasVariance = item.countedVarianceQty != null && item.countedVarianceQty !== 0;
        const isDiscrepant = item.latestCountStatus === "DISCREPANT";
        if (!hasVariance && !isDiscrepant) return false;
      }

      if (!q) return true;

      if (item.skuCode && item.skuCode.toLowerCase().includes(q)) return true;
      if (item.skuNameEn && item.skuNameEn.toLowerCase().includes(q)) return true;
      if (item.skuNameSi && item.skuNameSi.toLowerCase().includes(q)) return true;
      if (item.skuNameTa && item.skuNameTa.toLowerCase().includes(q)) return true;
      if (item.baseUomCode && item.baseUomCode.toLowerCase().includes(q)) return true;
      if (item.departmentNameEn && item.departmentNameEn.toLowerCase().includes(q)) return true;

      return false;
    });
  }, [summaries, searchQuery, statusFilter]);

  const toggleExpand = (skuId: string) => {
    setExpandedSkuIds((prev) => {
      const next = new Set(prev);
      if (next.has(skuId)) {
        next.delete(skuId);
      } else {
        next.add(skuId);
      }
      return next;
    });
  };

  const selectedSkuObject = useMemo<Sku | null>(() => {
    if (!selectedLot) return null;
    const summary = summaries.find((s) => s.skuId === selectedLot.skuId);
    if (!summary) return null;
    return {
      skuId: summary.skuId,
      skuCode: summary.skuCode,
      nameEn: summary.skuNameEn,
      nameSi: summary.skuNameSi ?? "",
      nameTa: summary.skuNameTa ?? "",
      status: "SHARED"
    } as unknown as Sku;
  }, [selectedLot, summaries]);

  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/reporting">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("inventory.back_dashboard").text}
        </Link>
      </div>

      <PageHeader
        icon="stock"
        title={t("inventory.title").text}
        actions={
          <>
            {canPrepare ? (
              <Link className="action-link" to="/inventory/opening/new">
                <span className="action-link__symbol" aria-hidden="true">
                  +
                </span>
                <span>{t("inventory.opening.new").text}</span>
              </Link>
            ) : null}

            <Link className="action-link action-link--primary" to="/inventory/transfers">
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

      <div className="stock-filter-pills" role="radiogroup" aria-label={t("inventory.filter.toggle").text}>
        <button
          type="button"
          className={`stock-pill-btn ${statusFilter === "all" ? "stock-pill-btn--active" : ""}`}
          onClick={() => setStatusFilter("all")}
          role="radio"
          aria-checked={statusFilter === "all"}
        >
          {t("inventory.stock.filter.all").text}
        </button>
        <button
          type="button"
          className={`stock-pill-btn ${statusFilter === "in_stock" ? "stock-pill-btn--active" : ""}`}
          onClick={() => setStatusFilter("in_stock")}
          role="radio"
          aria-checked={statusFilter === "in_stock"}
        >
          {t("inventory.stock.filter.in_stock").text}
        </button>
        <button
          type="button"
          className={`stock-pill-btn ${statusFilter === "out_of_stock" ? "stock-pill-btn--active" : ""}`}
          onClick={() => setStatusFilter("out_of_stock")}
          role="radio"
          aria-checked={statusFilter === "out_of_stock"}
        >
          {t("inventory.stock.filter.out_of_stock").text}
        </button>
        <button
          type="button"
          className={`stock-pill-btn ${statusFilter === "reserved" ? "stock-pill-btn--active" : ""}`}
          onClick={() => setStatusFilter("reserved")}
          role="radio"
          aria-checked={statusFilter === "reserved"}
        >
          {t("inventory.stock.filter.reserved").text}
        </button>
        <button
          type="button"
          className={`stock-pill-btn ${statusFilter === "in_transit" ? "stock-pill-btn--active" : ""}`}
          onClick={() => setStatusFilter("in_transit")}
          role="radio"
          aria-checked={statusFilter === "in_transit"}
        >
          {t("inventory.stock.filter.in_transit").text}
        </button>
        <button
          type="button"
          className={`stock-pill-btn ${statusFilter === "discrepant" ? "stock-pill-btn--active" : ""}`}
          onClick={() => setStatusFilter("discrepant")}
          role="radio"
          aria-checked={statusFilter === "discrepant"}
        >
          {t("inventory.stock.filter.discrepant").text}
        </button>
      </div>

      <section
        id="stock-filters"
        hidden={!filtersOpen}
        className="modern-filter-panel modern-filter-panel--stock stock-positions-filters"
      >
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>

        <div className="stock-advanced-selectors">
          <div className="stock-filter-select-wrap">
            <label htmlFor="filter-department" className="stock-filter-label">
              {t("inventory.stock.filter.department").text}
            </label>
            <select
              id="filter-department"
              value={departmentFilter}
              onChange={(e) => setDepartmentFilter(e.target.value)}
              className="stock-select-input"
            >
              <option value="">{t("inventory.stock.filter.all_departments").text}</option>
              {(departmentsQuery.data ?? []).map((dept: DepartmentCategory) => (
                <option key={dept.departmentId} value={dept.departmentId}>
                  {dept.nameEn}
                </option>
              ))}
            </select>
          </div>

          <div className="stock-filter-select-wrap">
            <label htmlFor="filter-uom" className="stock-filter-label">
              {t("inventory.stock.filter.uom").text}
            </label>
            <select
              id="filter-uom"
              value={uomFilter}
              onChange={(e) => setUomFilter(e.target.value)}
              className="stock-select-input"
            >
              <option value="">{t("inventory.stock.filter.all_uoms").text}</option>
              {(uomsQuery.data ?? []).map((uom: Uom) => (
                <option key={uom.uomCode} value={uom.uomCode}>
                  {uom.nameEn} ({uom.uomCode})
                </option>
              ))}
            </select>
          </div>
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

      {!locationId && (
        <div className="stock-empty-location-prompt">
          <p>{t("inventory.stock.select_location").text}</p>
        </div>
      )}

      {locationId && (
        <>
          <div className="stock-kpi-grid">
            <div className="stock-kpi-card stock-kpi-card--primary">
              <span className="stock-kpi-title">{t("inventory.stock.kpi.total_items").text}</span>
              <span className="stock-kpi-value">{kpis.totalItems}</span>
              <span className="stock-kpi-subtitle">
                {kpis.isSingleUom && kpis.singleUom ? kpis.singleUom : t("inventory.stock.kpi.mixed_uom").text}
              </span>
            </div>
            <div className="stock-kpi-card">
              <span className="stock-kpi-title">{t("inventory.stock.kpi.on_hand").text}</span>
              <span className="stock-kpi-value">
                {kpis.isSingleUom && kpis.singleUom
                  ? `${kpis.onHand} ${kpis.singleUom}`
                  : t("inventory.stock.kpi.unit_skus", undefined, { count: kpis.inStockCount }).text}
              </span>
            </div>
            <div className="stock-kpi-card stock-kpi-card--warning">
              <span className="stock-kpi-title">{t("inventory.stock.kpi.reserved").text}</span>
              <span className="stock-kpi-value">
                {kpis.isSingleUom && kpis.singleUom
                  ? `${kpis.reserved} ${kpis.singleUom}`
                  : t("inventory.stock.kpi.unit_skus", undefined, { count: kpis.reservedCount }).text}
              </span>
            </div>
            <div className="stock-kpi-card stock-kpi-card--info">
              <span className="stock-kpi-title">{t("inventory.stock.kpi.in_transit").text}</span>
              <span className="stock-kpi-value">
                {kpis.isSingleUom && kpis.singleUom
                  ? `+${kpis.inTransitIncoming} / -${kpis.inTransitOutgoing} ${kpis.singleUom}`
                  : t("inventory.stock.kpi.unit_skus", undefined, { count: kpis.inTransitCount }).text}
              </span>
            </div>
            <div
              className={`stock-kpi-card ${kpis.discrepancies > 0 ? "stock-kpi-card--alert" : "stock-kpi-card--success"}`}
            >
              <span className="stock-kpi-title">{t("inventory.stock.kpi.discrepancies").text}</span>
              <span className="stock-kpi-value">{kpis.discrepancies}</span>
            </div>
          </div>

          {summariesQuery.isLoading && <p>{t("inventory.loading").text}</p>}

          {summariesQuery.isError && (
            <p role="alert">
              {errorText(summariesQuery.error, t("inventory.error.generic").text)}
            </p>
          )}

          {summariesQuery.data && summariesQuery.data.length === 0 && (
            <p>{t("inventory.balances.empty").text}</p>
          )}

          {summariesQuery.data && summariesQuery.data.length > 0 && (
            <section className="modern-table-card stock-positions-card">
              <div className="modern-table-scroll stock-positions-scroll">
                <table className="modern-table stock-table stock-positions-table">
                  <colgroup>
                    <col className="col-expand" />
                    <col className="col-item" />
                    <col className="col-dept" />
                    <col className="col-uom" />
                    <col className="col-on-hand" />
                    <col className="col-reserved" />
                    <col className="col-available" />
                    <col className="col-in-transit" />
                    <col className="col-variance" />
                    <col className="col-actions" />
                  </colgroup>
                  <thead>
                    <tr>
                      <th className="col-expand" aria-label={t("inventory.stock.action.expand_lots").text} />
                      <th className="col-item">{t("inventory.stock.column.sku").text}</th>
                      <th className="col-dept">{t("inventory.stock.column.department").text}</th>
                      <th className="col-uom">{t("inventory.stock.column.uom").text}</th>
                      <th className="col-on-hand numeric-cell">{t("inventory.stock.column.on_hand").text}</th>
                      <th className="col-reserved numeric-cell">{t("inventory.stock.column.reserved").text}</th>
                      <th className="col-available numeric-cell">{t("inventory.stock.column.available").text}</th>
                      <th className="col-in-transit">{t("inventory.stock.column.in_transit").text}</th>
                      <th className="col-variance">{t("inventory.stock.column.count_variance").text}</th>
                      <th className="col-actions">{t("inventory.actions").text}</th>
                    </tr>
                  </thead>

                  <tbody>
                    {filteredSummaries.length === 0 ? (
                      <tr>
                        <td colSpan={10} className="empty-table-cell">
                          {t("inventory.find.none").text}
                        </td>
                      </tr>
                    ) : (
                      filteredSummaries.map((item) => {
                        const isExpanded = expandedSkuIds.has(item.skuId);
                        const lots = lotsBySkuId.get(item.skuId) ?? [];
                        const inTransitText =
                          item.inTransitIncomingQty > 0 || item.inTransitOutgoingQty > 0
                            ? `${item.inTransitIncomingQty > 0 ? `+${item.inTransitIncomingQty}` : ""} ${item.inTransitOutgoingQty > 0 ? `-${item.inTransitOutgoingQty}` : ""}`.trim()
                            : t("inventory.stock.in_transit.none").text;

                        return (
                          <React.Fragment key={item.skuId}>
                            <tr className={`stock-sku-row ${isExpanded ? "stock-sku-row--expanded" : ""}`}>
                              <td className="col-expand">
                                <button
                                  type="button"
                                  className="stock-expand-btn"
                                  onClick={() => toggleExpand(item.skuId)}
                                  aria-expanded={isExpanded}
                                  aria-label={
                                    isExpanded
                                      ? t("inventory.stock.action.collapse_lots").text
                                      : t("inventory.stock.action.expand_lots").text
                                  }
                                >
                                  {isExpanded ? "▾" : "▸"}
                                </button>
                              </td>

                              <td className="col-item">
                                <Link
                                  className="entity-link"
                                  to={`/inventory/locations/${locationId}/skus/${item.skuId}`}
                                >
                                  <span className="entity-link__icon" aria-hidden="true">
                                    <svg viewBox="0 0 24 24">
                                      <path d="M4 7 12 3l8 4-8 4-8-4Z" />
                                      <path d="M4 7v10l8 4 8-4V7" />
                                    </svg>
                                  </span>
                                  <span className="stock-sku-code">{item.skuCode}</span>
                                  {item.skuNameEn && (
                                    <span className="stock-sku-name"> — {item.skuNameEn}</span>
                                  )}
                                </Link>
                              </td>

                              <td className="col-dept">
                                {item.departmentNameEn ? (
                                  <span className="stock-pill-badge stock-pill-badge--dept">
                                    {item.departmentNameEn}
                                  </span>
                                ) : (
                                  "—"
                                )}
                              </td>

                              <td className="col-uom">
                                <span className="stock-pill-badge">{item.baseUomCode}</span>
                              </td>

                              <td className="col-on-hand numeric-cell">{item.qtyOnHand}</td>

                              <td className="col-reserved numeric-cell">
                                {item.reservedQty > 0 ? (
                                  <span className="stock-reserved-chip">{item.reservedQty}</span>
                                ) : (
                                  0
                                )}
                              </td>

                              <td className="col-available numeric-cell numeric-cell--strong">
                                {item.availableQty}
                              </td>

                              <td className="col-in-transit">
                                {item.inTransitIncomingQty > 0 || item.inTransitOutgoingQty > 0 ? (
                                  <div className="stock-in-transit-cell">
                                    {item.inTransitIncomingQty > 0 && (
                                      <span className="stock-direction-badge stock-direction-badge--incoming">
                                        {t("inventory.stock.in_transit.incoming", undefined, {
                                          qty: item.inTransitIncomingQty
                                        }).text}
                                      </span>
                                    )}
                                    {item.inTransitOutgoingQty > 0 && (
                                      <span className="stock-direction-badge stock-direction-badge--outgoing">
                                        {t("inventory.stock.in_transit.outgoing", undefined, {
                                          qty: item.inTransitOutgoingQty
                                        }).text}
                                      </span>
                                    )}
                                  </div>
                                ) : (
                                  <span className="stock-text-muted">{inTransitText}</span>
                                )}
                              </td>

                              <td className="col-variance">
                                {item.latestCountStatus ? (
                                  <div className="stock-variance-cell">
                                    <span
                                      className={`variance-badge variance-badge--${item.latestCountStatus === "DISCREPANT" ? "discrepant" : "matched"}`}
                                    >
                                      {t(
                                        item.latestCountStatus === "DISCREPANT"
                                          ? "inventory.stock.status.discrepant"
                                          : "inventory.stock.status.matched"
                                      ).text}
                                    </span>
                                    {item.countedVarianceQty != null && item.countedVarianceQty !== 0 && (
                                      <span className="stock-variance-qty">
                                        ({item.countedVarianceQty > 0 ? `+${item.countedVarianceQty}` : item.countedVarianceQty})
                                      </span>
                                    )}
                                  </div>
                                ) : (
                                  <span className="stock-text-muted">
                                    {t("inventory.stock.status.none").text}
                                  </span>
                                )}
                              </td>

                              <td className="col-actions">
                                <button
                                  type="button"
                                  className="button button--small button--secondary"
                                  onClick={() =>
                                    setExplainingSku({
                                      skuId: item.skuId,
                                      skuCode: item.skuCode,
                                      skuName: item.skuNameEn
                                    })
                                  }
                                >
                                  {t("inventory.stock.action.explain").text}
                                </button>
                              </td>
                            </tr>

                            {isExpanded && (
                              <tr className="stock-nested-row">
                                <td colSpan={10} className="stock-nested-cell">
                                  <div className="stock-nested-wrap">
                                    {lots.length === 0 ? (
                                      <p className="stock-nested-empty">
                                        {t("inventory.stock.explain.no_lots").text}
                                      </p>
                                    ) : (
                                      <table className="modern-table stock-nested-table">
                                        <thead>
                                          <tr>
                                            <th>{t("inventory.column.batch").text}</th>
                                            <th>{t("inventory.column.expiry").text}</th>
                                            <th>{t("inventory.column.condition").text}</th>
                                            <th>{t("inventory.column.fefo").text}</th>
                                            <th className="numeric-cell">
                                              {t("inventory.column.on_hand").text}
                                            </th>
                                            <th className="numeric-cell">
                                              {t("inventory.column.unit_cost").text}
                                            </th>
                                            {canReprice && (
                                              <th className="col-actions">
                                                {t("inventory.actions").text}
                                              </th>
                                            )}
                                          </tr>
                                        </thead>
                                        <tbody>
                                          {lots.map((lot) => (
                                            <tr key={lot.stockLotId}>
                                              <td>
                                                {isSyntheticBatchNo(lot.batchNo)
                                                  ? t("inventory.field.batch_not_tracked").text
                                                  : (lot.batchNo || "—")}
                                              </td>
                                              <td>
                                                {lot.expiryDate ? formatDate(lot.expiryDate) : "—"}
                                              </td>
                                              <td>
                                                <span className="condition-chip">
                                                  {t(`inventory.condition.${lot.condition}`).text}
                                                </span>
                                              </td>
                                              <td>
                                                {lot.expired ? (
                                                  <span className="condition-chip">
                                                    {t("inventory.lot.expired").text}
                                                  </span>
                                                ) : (
                                                  (lot.fefoRank ?? "—")
                                                )}
                                              </td>
                                              <td className="numeric-cell">{lot.qtyOnHand}</td>
                                              <td className="numeric-cell">
                                                {lot.unitCost != null ? (
                                                  <MoneyDisplay amount={lot.unitCost} />
                                                ) : (
                                                  "—"
                                                )}
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
                                          ))}
                                        </tbody>
                                      </table>
                                    )}
                                  </div>
                                </td>
                              </tr>
                            )}
                          </React.Fragment>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </>
      )}

      <StockExplanationModal
        isOpen={explainingSku !== null}
        onClose={() => setExplainingSku(null)}
        locationId={locationId}
        skuId={explainingSku?.skuId ?? null}
        skuCode={explainingSku?.skuCode}
        skuName={explainingSku?.skuName}
      />

      <StockRepriceModal
        isOpen={selectedLot !== null}
        onClose={() => setSelectedLot(null)}
        lot={selectedLot}
        sku={selectedSkuObject}
        onSuccess={() => {
          queryClient.invalidateQueries({ queryKey: ["inventory", "balances", locationId] });
          queryClient.invalidateQueries({ queryKey: ["inventory", "stock-summaries", locationId] });
        }}
      />
    </main>
  );
}
