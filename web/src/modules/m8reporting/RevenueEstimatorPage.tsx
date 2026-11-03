import { useState, useMemo } from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { locationText, skuText } from "../../shell/i18n/localName";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { ApiProblem } from "../../shell/api/client";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import {
  useReportingApi,
  type RevenueEstimateRequest,
  type RevenueEstimateResponse,
  type Sku
} from "./reportingApi";
import "./reporting.css";

interface MixItem {
  skuId: string;
  skuCode: string;
  name: string;
  uomCode: string;
  mixPercentage: number;
}

export function RevenueEstimatorPage() {
  const t = useT();
  const intl = useIntl();
  const formatDate = useFormatDate();
  const api = useReportingApi();
  const key = useIdempotencyKey();

  const [targetRevenue, setTargetRevenue] = useState<string>("100000.00");
  const [locationId, setLocationId] = useState<string>("");
  const [pricingBasis, setPricingBasis] = useState<"RETAIL" | "COMMERCIAL">("RETAIL");
  const [asOfDate, setAsOfDate] = useState<string>(businessToday());
  const [mixItems, setMixItems] = useState<MixItem[]>([]);
  const [selectedSkuId, setSelectedSkuId] = useState<string>("");
  const [skuSearch, setSkuSearch] = useState<string>("");

  const [estimateResult, setEstimateResult] = useState<RevenueEstimateResponse | null>(null);
  const [estimateError, setEstimateError] = useState<string | null>(null);

  // Queries
  const locationsQuery = useQuery({
    queryKey: ["reporting", "locations"],
    queryFn: () => api.locations(),
    staleTime: Infinity
  });

  const skusQuery = useQuery({
    queryKey: ["reporting", "skus-search", skuSearch],
    queryFn: () => api.searchSkus(skuSearch),
    staleTime: 60000
  });

  // Calculate sum of mix percentages
  const mixTotal = useMemo(() => {
    const sum = mixItems.reduce((acc, item) => acc + (Number(item.mixPercentage) || 0), 0);
    return Math.round(sum * 100) / 100;
  }, [mixItems]);

  const isMixValid = Math.abs(mixTotal - 100) < 0.001;
  const isTargetValid = Number(targetRevenue) > 0;
  const canCalculate = isTargetValid && locationId !== "" && mixItems.length > 0 && isMixValid;

  // Mutation
  const estimateMutation = useMutation({
    mutationFn: (request: RevenueEstimateRequest) => api.estimateRevenue(request, key.current()),
    onSuccess: (data) => {
      key.next();
      setEstimateResult(data);
      setEstimateError(null);
    },
    onError: (error) => {
      key.next();
      if (error instanceof ApiProblem) {
        if (error.problem.code === "m8.estimator.unresolved_price") {
          const skuParam = String(error.problem.params?.sku ?? error.problem.title ?? "");
          setEstimateError(
            t("reporting.estimator.error.unresolved_price", undefined, { sku: skuParam }).text
          );
        } else {
          setEstimateError(error.problem.title || t("reporting.estimator.error.generic").text);
        }
      } else {
        setEstimateError(t("reporting.estimator.error.generic").text);
      }
    }
  });

  function handleAddSku() {
    if (!selectedSkuId) return;
    const existing = mixItems.find((i) => i.skuId === selectedSkuId);
    if (existing) return;

    const skuObj = skusQuery.data?.find((s: Sku) => s.skuId === selectedSkuId);
    if (!skuObj) return;

    const remaining = Math.max(0, Math.round((100 - mixTotal) * 100) / 100);
    const newItem: MixItem = {
      skuId: skuObj.skuId,
      skuCode: skuObj.skuCode,
      name: skuText(skuObj, intl.locale),
      uomCode: skuObj.baseUomCode,
      mixPercentage: remaining > 0 ? remaining : 0
    };

    setMixItems([...mixItems, newItem]);
    setSelectedSkuId("");
  }

  function handleRemoveSku(skuId: string) {
    setMixItems(mixItems.filter((i) => i.skuId !== skuId));
  }

  function handleUpdateMix(skuId: string, value: string) {
    const num = parseFloat(value);
    setMixItems(
      mixItems.map((item) => (item.skuId === skuId ? { ...item, mixPercentage: isNaN(num) ? 0 : num } : item))
    );
  }

  function handleCalculate() {
    if (!canCalculate) return;
    setEstimateError(null);
    estimateMutation.mutate({
      targetRevenue: targetRevenue,
      locationId: locationId || null,
      priceType: pricingBasis,
      businessDate: asOfDate,
      items: mixItems.map((item) => ({
        skuId: item.skuId,
        mixPercent: Number(item.mixPercentage).toFixed(2)
      }))
    });
  }

  // Location lookup for assumptions
  const selectedLocation = locationsQuery.data?.find((loc) => loc.locationId === locationId);
  const locationDisplayName = selectedLocation ? locationText(selectedLocation, intl.locale) : locationId;

  return (
    <div className="reporting-page">
      <PageHeader
        icon="pricing"
        title={t("reporting.estimator.title").text}
      />

      {/* Inputs Card */}
      <div className="shop-targets-card">
        <div className="estimator-form-grid">
          <div className="estimator-field">
            <label htmlFor="target-revenue-input">{t("reporting.estimator.target_revenue").text}</label>
            <input
              id="target-revenue-input"
              type="number"
              min="0.01"
              step="0.01"
              className="form-control"
              value={targetRevenue}
              onChange={(e) => setTargetRevenue(e.target.value)}
            />
          </div>

          <div className="estimator-field">
            <label htmlFor="location-select">{t("reporting.estimator.location").text}</label>
            <select
              id="location-select"
              className="form-control"
              value={locationId}
              onChange={(e) => setLocationId(e.target.value)}
            >
              <option value="">{t("reporting.estimator.location.select").text}</option>
              {locationsQuery.data?.map((loc) => (
                <option key={loc.locationId} value={loc.locationId}>
                  {locationText(loc, intl.locale)}
                </option>
              ))}
            </select>
          </div>

          <div className="estimator-field">
            <label htmlFor="pricing-basis-select">{t("reporting.estimator.pricing_basis").text}</label>
            <select
              id="pricing-basis-select"
              className="form-control"
              value={pricingBasis}
              onChange={(e) => setPricingBasis(e.target.value as "RETAIL" | "COMMERCIAL")}
            >
              <option value="RETAIL">{t("reporting.estimator.pricing_basis.retail").text}</option>
              <option value="COMMERCIAL">{t("reporting.estimator.pricing_basis.commercial").text}</option>
            </select>
          </div>

          <div className="estimator-field">
            <label htmlFor="as-of-date-input">{t("reporting.estimator.date").text}</label>
            <input
              id="as-of-date-input"
              type="date"
              className="form-control"
              value={asOfDate}
              onChange={(e) => setAsOfDate(e.target.value)}
            />
          </div>
        </div>
      </div>

      {/* Product Mix Card */}
      <div className="shop-targets-card">
        <div className="estimator-mix-header">
          <h2 className="section-title">{t("reporting.estimator.mix_section").text}</h2>
          <span
            className={`estimator-badge ${
              isMixValid && mixItems.length > 0 ? "estimator-badge-valid" : "estimator-badge-invalid"
            }`}
          >
            {t("reporting.estimator.mix_total", undefined, { total: mixTotal.toFixed(2) }).text}
          </span>
        </div>

        {/* Add SKU bar */}
        <div className="estimator-sku-add-bar">
          <input
            type="text"
            className="form-control estimator-sku-search-input"
            placeholder={t("reporting.estimator.search_placeholder").text}
            value={skuSearch}
            onChange={(e) => setSkuSearch(e.target.value)}
          />
          <label htmlFor="sku-selector" className="visually-hidden">
            {t("reporting.estimator.select_sku").text}
          </label>
          <select
            id="sku-selector"
            aria-label={t("reporting.estimator.select_sku").text}
            className="form-control"
            value={selectedSkuId}
            onChange={(e) => setSelectedSkuId(e.target.value)}
          >
            <option value="">{t("reporting.estimator.select_sku").text}</option>
            {skusQuery.data
              ?.filter((s: Sku) => !mixItems.some((item) => item.skuId === s.skuId))
              .map((sku: Sku) => (
                <option key={sku.skuId} value={sku.skuId}>
                  {skuText(sku, intl.locale)} ({sku.baseUomCode})
                </option>
              ))}
          </select>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={handleAddSku}
            disabled={!selectedSkuId}
          >
            {t("reporting.estimator.add_sku").text}
          </button>
        </div>

        {/* Selected Mix Items Table */}
        {mixItems.length === 0 ? (
          <p className="reporting-empty">{t("reporting.estimator.no_skus").text}</p>
        ) : (
          <div className="table-responsive">
            <table className="estimator-sku-table">
              <thead>
                <tr>
                  <th>{t("reporting.estimator.table.sku").text}</th>
                  <th>{t("reporting.estimator.table.uom").text}</th>
                  <th className="estimator-col-mix">{t("reporting.estimator.mix_percent").text}</th>
                  <th className="estimator-col-actions">{t("reporting.estimator.actions").text}</th>
                </tr>
              </thead>
              <tbody>
                {mixItems.map((item) => (
                  <tr key={item.skuId}>
                    <td>
                      <strong>{item.skuCode}</strong> {item.name}
                    </td>
                    <td>{item.uomCode}</td>
                    <td>
                      <input
                        type="number"
                        min="0.01"
                        max="100"
                        step="0.01"
                        className="form-control estimator-mix-input"
                        value={item.mixPercentage}
                        onChange={(e) => handleUpdateMix(item.skuId, e.target.value)}
                      />
                    </td>
                    <td>
                      <button
                        type="button"
                        className="btn btn--danger btn--sm"
                        onClick={() => handleRemoveSku(item.skuId)}
                      >
                        {t("reporting.estimator.remove").text}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Validation Warning */}
        {mixItems.length > 0 && !isMixValid && (
          <div className="pnl-pending-banner estimator-warning-margin">
            {t("reporting.estimator.mix_warning", undefined, { total: mixTotal.toFixed(2) }).text}
          </div>
        )}

        {/* Calculate Action */}
        <div className="estimator-calculate-bar">
          <button
            type="button"
            className="btn btn--primary"
            disabled={!canCalculate || estimateMutation.isPending}
            onClick={handleCalculate}
          >
            {estimateMutation.isPending
              ? t("reporting.estimator.calculating").text
              : t("reporting.estimator.btn_calculate").text}
          </button>
        </div>
      </div>

      {/* Error Banner */}
      {estimateError && <div className="estimator-error-banner">{estimateError}</div>}

      {/* Results Section */}
      {estimateResult && (
        <div className="shop-targets-card">
          <h2 className="section-title">{t("reporting.estimator.results_title").text}</h2>

          {/* KPI Summary Grid */}
          <div className="targets-kpi-grid">
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.estimator.kpi.target").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={estimateResult.targetRevenue} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.estimator.kpi.estimated").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={estimateResult.totalEstimatedRevenue} />
              </span>
            </div>
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.estimator.kpi.variance").text}</span>
              <span
                className={`targets-kpi-value ${
                  Number(estimateResult.targetVariance) >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"
                }`}
              >
                <MoneyDisplay amount={estimateResult.targetVariance} />
              </span>
            </div>
          </div>

          {/* Detailed Item Breakdown Table */}
          <div className="table-responsive">
            <table className="reporting-data-table estimator-results-table">
              <thead>
                <tr>
                  <th>{t("reporting.estimator.table.sku").text}</th>
                  <th>{t("reporting.estimator.table.price").text}</th>
                  <th>{t("reporting.estimator.mix_percent").text}</th>
                  <th>{t("reporting.estimator.table.share").text}</th>
                  <th>{t("reporting.estimator.table.raw_qty").text}</th>
                  <th>{t("reporting.estimator.table.rounded_qty").text}</th>
                  <th>{t("reporting.estimator.table.estimated_revenue").text}</th>
                  <th>{t("reporting.estimator.table.rounding_diff").text}</th>
                </tr>
              </thead>
              <tbody>
                {estimateResult.items.map((item) => (
                  <tr key={item.skuId}>
                    <td>
                      <strong>{item.skuCode}</strong> {item.skuName}
                    </td>
                    <td>
                      <MoneyDisplay amount={item.unitPrice} />
                    </td>
                    <td>{Number(item.mixPercent).toFixed(2)}%</td>
                    <td>
                      <MoneyDisplay amount={item.targetShare} />
                    </td>
                    <td>
                      {Number(item.rawQuantity).toFixed(3)} {item.uomCode}
                    </td>
                    <td>
                      <strong>{item.roundedQuantity}</strong> {item.uomCode}
                    </td>
                    <td>
                      <MoneyDisplay amount={item.estimatedRevenue} />
                    </td>
                    <td>
                      <MoneyDisplay amount={item.roundingVariance} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Variance Breakdown Card */}
          <div className="pnl-pending-banner estimator-variance-card">
            <strong>{t("reporting.estimator.variance_breakdown").text}: </strong>
            {t("reporting.estimator.variance_desc", undefined, {
              variance: Number(estimateResult.targetVariance).toFixed(2)
            }).text}
          </div>

          {/* Assumptions Box */}
          <div className="estimator-assumptions-box">
            <strong>{t("reporting.estimator.assumptions").text}: </strong>
            {t("reporting.estimator.assumptions_desc", undefined, {
              location: locationDisplayName,
              date: formatDate(estimateResult.assumptions.businessDate),
              basis:
                estimateResult.assumptions.priceType === "RETAIL"
                  ? t("reporting.estimator.pricing_basis.retail").text
                  : t("reporting.estimator.pricing_basis.commercial").text
            }).text}
          </div>
        </div>
      )}
    </div>
  );
}
