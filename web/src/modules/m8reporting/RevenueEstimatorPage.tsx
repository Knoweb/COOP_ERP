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
import { EstimatorSelect, type EstimatorSelectOption } from "./EstimatorSelect";
import { EstimatorDatePicker } from "./EstimatorDatePicker";
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

  const mixTotal = useMemo(() => {
    const sum = mixItems.reduce((acc, item) => acc + (Number(item.mixPercentage) || 0), 0);
    return Math.round(sum * 100) / 100;
  }, [mixItems]);

  const isMixValid = Math.abs(mixTotal - 100) < 0.001;
  const isTargetValid = Number(targetRevenue) > 0;
  const canCalculate = isTargetValid && locationId !== "" && mixItems.length > 0 && isMixValid;

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
      mixItems.map((item) =>
        item.skuId === skuId ? { ...item, mixPercentage: isNaN(num) ? 0 : num } : item
      )
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

  const selectedLocation = locationsQuery.data?.find((loc) => loc.locationId === locationId);
  const locationDisplayName = selectedLocation
    ? locationText(selectedLocation, intl.locale)
    : locationId;

  const locationOptions: EstimatorSelectOption<string>[] = useMemo(
    () =>
      (locationsQuery.data ?? []).map((loc) => ({
        value: loc.locationId,
        label: locationText(loc, intl.locale)
      })),
    [locationsQuery.data, intl.locale]
  );

  const pricingBasisOptions: EstimatorSelectOption<"RETAIL" | "COMMERCIAL">[] = useMemo(
    () => [
      { value: "RETAIL", label: t("reporting.estimator.pricing_basis.retail").text },
      { value: "COMMERCIAL", label: t("reporting.estimator.pricing_basis.commercial").text }
    ],
    [t]
  );

  const availableSkuOptions: EstimatorSelectOption<string>[] = useMemo(
    () =>
      (skusQuery.data ?? [])
        .filter((s: Sku) => !mixItems.some((item) => item.skuId === s.skuId))
        .map((sku: Sku) => ({
          value: sku.skuId,
          label: `${skuText(sku, intl.locale)} (${sku.baseUomCode})`
        })),
    [skusQuery.data, mixItems, intl.locale]
  );

  const varianceTone =
    estimateResult && Number(estimateResult.targetVariance) < 0 ? "alert" : "success";

  return (
    <main className="shell-page">
      <PageHeader icon="pricing" title={t("reporting.estimator.title").text} />

      <p className="sales-estimator-subtitle">{t("reporting.estimator.subtitle").text}</p>

      {/* Parameter & Basis Filter Panel */}
      <section className="modern-filter-panel sales-estimator-filter-panel">
        <div className="sales-estimator-filter-controls">
          <div className="sales-estimator-field sales-estimator-field--revenue">
            <label className="sales-estimator-field__label" htmlFor="target-revenue-input">
              {t("reporting.estimator.target_revenue").text}
            </label>
            <input
              id="target-revenue-input"
              type="number"
              min="0.01"
              step="0.01"
              className="sales-estimator-input sales-estimator-input--numeric"
              value={targetRevenue}
              onChange={(e) => setTargetRevenue(e.target.value)}
            />
          </div>

          <div className="sales-estimator-field sales-estimator-field--location">
            <label className="sales-estimator-field__label" htmlFor="location-select">
              {t("reporting.estimator.location").text}
            </label>
            <EstimatorSelect
              id="location-select"
              aria-label={t("reporting.estimator.location").text}
              value={locationId}
              options={locationOptions}
              placeholder={t("reporting.estimator.location.select").text}
              onChange={setLocationId}
            />
          </div>

          <div className="sales-estimator-field sales-estimator-field--basis">
            <label className="sales-estimator-field__label" htmlFor="pricing-basis-select">
              {t("reporting.estimator.pricing_basis").text}
            </label>
            <EstimatorSelect
              id="pricing-basis-select"
              aria-label={t("reporting.estimator.pricing_basis").text}
              value={pricingBasis}
              options={pricingBasisOptions}
              onChange={(next) => setPricingBasis(next as "RETAIL" | "COMMERCIAL")}
            />
          </div>

          <div className="sales-estimator-field sales-estimator-field--date">
            <label className="sales-estimator-field__label" htmlFor="as-of-date-input">
              {t("reporting.estimator.date").text}
            </label>
            <EstimatorDatePicker
              id="as-of-date-input"
              label={t("reporting.estimator.date").text}
              value={asOfDate}
              onChange={setAsOfDate}
            />
          </div>
        </div>
      </section>

      {/* Product Mix Planning Section */}
      <section className="sales-estimator-section">
        <div className="sales-estimator-section__header">
          <div className="sales-estimator-section__title-group">
            <span className="sales-estimator-section__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <polygon points="12 2 2 7 12 12 22 7 12 2" />
                <polyline points="2 17 12 22 22 17" />
                <polyline points="2 12 12 17 22 12" />
              </svg>
            </span>
            <h2>{t("reporting.estimator.mix_section").text}</h2>
          </div>

          <span
            className={`sales-estimator-mix-pill ${
              isMixValid && mixItems.length > 0
                ? "sales-estimator-mix-pill--valid"
                : "sales-estimator-mix-pill--invalid"
            }`}
          >
            {t("reporting.estimator.mix_total", undefined, { total: mixTotal.toFixed(2) }).text}
          </span>
        </div>

        <div className="sales-estimator-planner-card">
          {/* Add SKU Bar */}
          <div className="sales-estimator-sku-add-bar">
            <div className="sales-estimator-search-wrap">
              <span className="sales-estimator-search-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <circle cx="11" cy="11" r="8" />
                  <path d="m21 21-4.3-4.3" />
                </svg>
              </span>
              <input
                type="text"
                className="sales-estimator-search-input"
                aria-label={t("reporting.estimator.search_placeholder").text}
                placeholder={t("reporting.estimator.search_placeholder").text}
                value={skuSearch}
                onChange={(e) => setSkuSearch(e.target.value)}
              />
            </div>

            <div className="sales-estimator-sku-select-wrap">
              <label htmlFor="sku-selector" className="visually-hidden">
                {t("reporting.estimator.select_sku").text}
              </label>
              <EstimatorSelect
                id="sku-selector"
                aria-label={t("reporting.estimator.select_sku").text}
                value={selectedSkuId}
                options={availableSkuOptions}
                placeholder={t("reporting.estimator.select_sku").text}
                onChange={setSelectedSkuId}
              />
            </div>

            <button
              type="button"
              className="sales-estimator-add-btn"
              onClick={handleAddSku}
              disabled={!selectedSkuId}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              <span>{t("reporting.estimator.add_sku").text}</span>
            </button>
          </div>

          {/* Selected Mix Items Table or Empty State */}
          {mixItems.length === 0 ? (
            <div className="sales-estimator-empty-box">
              <span className="sales-estimator-empty-box__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
                  <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
                  <line x1="12" y1="22.08" x2="12" y2="12" />
                </svg>
              </span>
              <p>{t("reporting.estimator.no_skus").text}</p>
            </div>
          ) : (
            <div className="modern-table-card sales-estimator-mix-table-card">
              <div className="modern-table-scroll">
                <table
                  className="modern-table reporting-data-table sales-estimator-mix-table"
                  aria-label={t("reporting.estimator.mix_section").text}
                >
                  <thead>
                    <tr>
                      <th scope="col">{t("reporting.estimator.table.sku").text}</th>
                      <th scope="col">{t("reporting.estimator.table.uom").text}</th>
                      <th scope="col" className="sales-estimator-col-mix">
                        {t("reporting.estimator.mix_percent").text}
                      </th>
                      <th scope="col" className="sales-estimator-col-actions">
                        {t("reporting.estimator.actions").text}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {mixItems.map((item) => (
                      <tr key={item.skuId}>
                        <td>
                          <div className="sales-estimator-sku-cell">
                            <strong className="sales-estimator-code-pill">{item.skuCode}</strong>
                            <span className="sales-estimator-sku-name">{item.name}</span>
                          </div>
                        </td>
                        <td>
                          <span className="sales-estimator-uom-pill">{item.uomCode}</span>
                        </td>
                        <td className="sales-estimator-col-mix">
                          <input
                            type="number"
                            min="0.01"
                            max="100"
                            step="0.01"
                            aria-label={`${item.skuCode} ${t("reporting.estimator.mix_percent").text}`}
                            className="sales-estimator-mix-input"
                            value={item.mixPercentage}
                            onChange={(e) => handleUpdateMix(item.skuId, e.target.value)}
                          />
                        </td>
                        <td className="sales-estimator-col-actions">
                          <button
                            type="button"
                            className="sales-estimator-remove-btn"
                            onClick={() => handleRemoveSku(item.skuId)}
                          >
                            <svg viewBox="0 0 24 24" aria-hidden="true">
                              <polyline points="3 6 5 6 21 6" />
                              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                            </svg>
                            <span>{t("reporting.estimator.remove").text}</span>
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Validation Warning */}
          {mixItems.length > 0 && !isMixValid && (
            <div className="sales-estimator-warning-banner" role="status">
              <span className="sales-estimator-banner-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
                  <line x1="12" y1="9" x2="12" y2="13" />
                  <line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
              </span>
              <span>
                {t("reporting.estimator.mix_warning", undefined, { total: mixTotal.toFixed(2) }).text}
              </span>
            </div>
          )}

          {/* Calculate Action */}
          <div className="sales-estimator-calculate-bar">
            <button
              type="button"
              className="sales-estimator-calculate-btn"
              disabled={!canCalculate || estimateMutation.isPending}
              onClick={handleCalculate}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <rect x="4" y="2" width="16" height="20" rx="2" />
                <line x1="8" y1="6" x2="16" y2="6" />
                <line x1="16" y1="14" x2="16" y2="18" />
                <path d="M16 10h.01" />
                <path d="M12 10h.01" />
                <path d="M8 10h.01" />
                <path d="M12 14h.01" />
                <path d="M8 14h.01" />
                <path d="M12 18h.01" />
                <path d="M8 18h.01" />
              </svg>
              <span>
                {estimateMutation.isPending
                  ? t("reporting.estimator.calculating").text
                  : t("reporting.estimator.btn_calculate").text}
              </span>
            </button>
          </div>
        </div>
      </section>

      {/* Error Banner */}
      {estimateError && (
        <div className="sales-estimator-error-banner" role="alert">
          <span className="sales-estimator-banner-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
          </span>
          <span>{estimateError}</span>
        </div>
      )}

      {/* Results Section */}
      {estimateResult && (
        <section className="sales-estimator-section">
          <div className="sales-estimator-section__header">
            <div className="sales-estimator-section__title-group">
              <span className="sales-estimator-section__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <line x1="18" y1="20" x2="18" y2="10" />
                  <line x1="12" y1="20" x2="12" y2="4" />
                  <line x1="6" y1="20" x2="6" y2="14" />
                </svg>
              </span>
              <h2>{t("reporting.estimator.results_title").text}</h2>
            </div>
            <span className="sales-estimator-count-pill">{estimateResult.items.length}</span>
          </div>

          {/* KPI Summary Grid */}
          <div className="sales-estimator-kpi-grid">
            <div className="sales-estimator-kpi-card sales-estimator-kpi-card--primary">
              <div className="sales-estimator-kpi-card__top">
                <span className="sales-estimator-kpi-label">
                  {t("reporting.estimator.kpi.target").text}
                </span>
                <span className="sales-estimator-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <circle cx="12" cy="12" r="6" />
                    <circle cx="12" cy="12" r="2" />
                  </svg>
                </span>
              </div>
              <div className="sales-estimator-kpi-card__bottom">
                <span className="sales-estimator-kpi-value">
                  <MoneyDisplay amount={estimateResult.targetRevenue} />
                </span>
              </div>
            </div>

            <div className="sales-estimator-kpi-card sales-estimator-kpi-card--navy">
              <div className="sales-estimator-kpi-card__top">
                <span className="sales-estimator-kpi-label">
                  {t("reporting.estimator.kpi.estimated").text}
                </span>
                <span className="sales-estimator-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
                    <polyline points="17 6 23 6 23 12" />
                  </svg>
                </span>
              </div>
              <div className="sales-estimator-kpi-card__bottom">
                <span className="sales-estimator-kpi-value">
                  <MoneyDisplay amount={estimateResult.totalEstimatedRevenue} />
                </span>
              </div>
            </div>

            <div className={`sales-estimator-kpi-card sales-estimator-kpi-card--${varianceTone}`}>
              <div className="sales-estimator-kpi-card__top">
                <span className="sales-estimator-kpi-label">
                  {t("reporting.estimator.kpi.variance").text}
                </span>
                <span className="sales-estimator-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M12 2v20" />
                    <path d="m17 5-5-3-5 3" />
                    <path d="m17 19-5 3-5-3" />
                  </svg>
                </span>
              </div>
              <div className="sales-estimator-kpi-card__bottom">
                <span
                  className={`sales-estimator-kpi-value ${
                    Number(estimateResult.targetVariance) >= 0
                      ? "sales-estimator-kpi-positive"
                      : "sales-estimator-kpi-negative"
                  }`}
                >
                  <MoneyDisplay amount={estimateResult.targetVariance} />
                </span>
              </div>
            </div>
          </div>

          {/* Detailed Item Breakdown Table */}
          <div className="modern-table-card sales-estimator-results-card">
            <div className="modern-table-scroll">
              <table
                className="modern-table reporting-data-table sales-estimator-results-table"
                aria-label={t("reporting.estimator.results_title").text}
              >
                <thead>
                  <tr>
                    <th scope="col">{t("reporting.estimator.table.sku").text}</th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.table.price").text}
                    </th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.mix_percent").text}
                    </th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.table.share").text}
                    </th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.table.raw_qty").text}
                    </th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.table.rounded_qty").text}
                    </th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.table.estimated_revenue").text}
                    </th>
                    <th scope="col" className="sales-estimator-num">
                      {t("reporting.estimator.table.rounding_diff").text}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {estimateResult.items.map((item) => (
                    <tr key={item.skuId}>
                      <td>
                        <div className="sales-estimator-sku-cell">
                          <strong className="sales-estimator-code-pill">{item.skuCode}</strong>
                          <span className="sales-estimator-sku-name">{item.skuName}</span>
                        </div>
                      </td>
                      <td className="sales-estimator-num">
                        <MoneyDisplay amount={item.unitPrice} />
                      </td>
                      <td className="sales-estimator-num">{Number(item.mixPercent).toFixed(2)}%</td>
                      <td className="sales-estimator-num">
                        <MoneyDisplay amount={item.targetShare} />
                      </td>
                      <td className="sales-estimator-num">
                        {Number(item.rawQuantity).toFixed(3)} {item.uomCode}
                      </td>
                      <td className="sales-estimator-num">
                        <strong>{item.roundedQuantity}</strong> {item.uomCode}
                      </td>
                      <td className="sales-estimator-num">
                        <MoneyDisplay amount={item.estimatedRevenue} />
                      </td>
                      <td className="sales-estimator-num">
                        <MoneyDisplay amount={item.roundingVariance} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Callouts Row: Variance Breakdown & Planning Assumptions */}
          <div className="sales-estimator-callouts">
            <div className="sales-estimator-callout sales-estimator-callout--warning">
              <span className="sales-estimator-callout__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="M12 2v20" />
                  <path d="m17 5-5-3-5 3" />
                  <path d="m17 19-5 3-5-3" />
                </svg>
              </span>
              <div className="sales-estimator-callout__body">
                <strong>{t("reporting.estimator.variance_breakdown").text}: </strong>
                <span>
                  {t("reporting.estimator.variance_desc", undefined, {
                    variance: Number(estimateResult.targetVariance).toFixed(2)
                  }).text}
                </span>
              </div>
            </div>

            <div className="sales-estimator-callout sales-estimator-callout--info">
              <span className="sales-estimator-callout__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="16" x2="12" y2="12" />
                  <line x1="12" y1="8" x2="12.01" y2="8" />
                </svg>
              </span>
              <div className="sales-estimator-callout__body">
                <strong>{t("reporting.estimator.assumptions").text}: </strong>
                <span>
                  {t("reporting.estimator.assumptions_desc", undefined, {
                    location: locationDisplayName,
                    date: formatDate(estimateResult.assumptions.businessDate),
                    basis:
                      estimateResult.assumptions.priceType === "RETAIL"
                        ? t("reporting.estimator.pricing_basis.retail").text
                        : t("reporting.estimator.pricing_basis.commercial").text
                  }).text}
                </span>
              </div>
            </div>
          </div>
        </section>
      )}
    </main>
  );
}
