import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { useCatalogueApi, type TaxCategory, type TaxRate } from "./catalogueApi";
import { errorText } from "./skuView";
import "./catalogue.css";

export function TaxRatesPage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canPublish = useHasPermission("cat.tax.publish");
  const formatDate = useFormatDate();

  const categoryIdempotency = useIdempotencyKey();
  const rateIdempotency = useIdempotencyKey();

  const [selectedCategoryId, setSelectedCategoryId] = useState<string>("");
  const [successMessage, setSuccessMessage] = useState<string>("");

  // Define Category Form State
  const [categoryCode, setCategoryCode] = useState<string>("");
  const [categoryNameEn, setCategoryNameEn] = useState<string>("");
  const [categoryNameSi, setCategoryNameSi] = useState<string>("");
  const [categoryNameTa, setCategoryNameTa] = useState<string>("");

  // Publish Rate Form State
  const [rateTaxCategoryId, setRateTaxCategoryId] = useState<string>("");
  const [ratePercent, setRatePercent] = useState<string>("18.00");
  const todayIso = businessToday();
  const [applyFrom, setApplyFrom] = useState<string>(todayIso);

  const categoriesQuery = useQuery({
    queryKey: ["catalogue", "tax", "categories"],
    queryFn: () => api.listTaxCategories()
  });

  const ratesQuery = useQuery({
    queryKey: ["catalogue", "tax", "rates", selectedCategoryId],
    queryFn: () => api.listTaxRates(selectedCategoryId || undefined)
  });

  const defineCategoryMutation = useMutation({
    mutationFn: () =>
      api.defineTaxCategory(
        {
          code: categoryCode.trim().toUpperCase(),
          nameEn: categoryNameEn.trim(),
          nameSi: categoryNameSi.trim() || undefined,
          nameTa: categoryNameTa.trim() || undefined
        },
        categoryIdempotency.current()
      ),
    onSuccess: (newCategory) => {
      categoryIdempotency.next();
      setCategoryCode("");
      setCategoryNameEn("");
      setCategoryNameSi("");
      setCategoryNameTa("");
      setSuccessMessage(t("catalogue.tax.success.category").text);
      queryClient.invalidateQueries({ queryKey: ["catalogue", "tax", "categories"] });
      if (!rateTaxCategoryId && newCategory.taxCategoryId) {
        setRateTaxCategoryId(newCategory.taxCategoryId);
      }
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        categoryIdempotency.next();
      }
    }
  });

  const publishRateMutation = useMutation({
    mutationFn: () =>
      api.publishTaxRate(
        {
          taxCategoryId: rateTaxCategoryId,
          ratePercent: parseFloat(ratePercent),
          applyFrom
        },
        rateIdempotency.current()
      ),
    onSuccess: () => {
      rateIdempotency.next();
      setSuccessMessage(t("catalogue.tax.success.rate").text);
      queryClient.invalidateQueries({ queryKey: ["catalogue", "tax", "rates"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        rateIdempotency.next();
      }
    }
  });

  const handleDefineCategorySubmit = (e: FormEvent) => {
    e.preventDefault();
    setSuccessMessage("");
    defineCategoryMutation.mutate();
  };

  const handlePublishRateSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSuccessMessage("");
    publishRateMutation.mutate();
  };

  const getRateStatus = (rate: TaxRate): { state: ChipState; label: string } => {
    if (rate.effectiveFrom > todayIso) {
      return { state: "draft", label: t("catalogue.tax.status.scheduled").text };
    }
    if (rate.effectiveTo && rate.effectiveTo < todayIso) {
      return { state: "void", label: t("catalogue.tax.status.past").text };
    }
    return { state: "issued", label: t("catalogue.tax.status.in_force").text };
  };

  const categories = categoriesQuery.data ?? [];
  const rates = ratesQuery.data ?? [];

  return (
    <main className="shell-page" id="tax-rates-page">
      <Link className="back-link" to="/catalogue">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        {t("catalogue.back").text}
      </Link>

      <PageHeader
        icon="catalogue"
        title={t("catalogue.tax.title").text}
      />

      {successMessage && (
        <div className="tax-success-banner" role="status">
          {successMessage}
        </div>
      )}

      {/* Forms Section for Authorized Users */}
      {canPublish && (
        <div className="tax-forms-grid">
          {/* Define Category Card */}
          <section className="modern-table-card tax-card">
            <h2 className="tax-card-title">
              {t("catalogue.tax.define_category").text}
            </h2>
            <form onSubmit={handleDefineCategorySubmit} className="catalogue-form-row">
              <label className="catalogue-form-field">
                <span className="tax-field-required">{t("catalogue.tax.field.code").text} *</span>
                <input
                  id="tax-category-code-input"
                  type="text"
                  maxLength={12}
                  required
                  value={categoryCode}
                  onChange={(e) => setCategoryCode(e.target.value.toUpperCase())}
                  placeholder={t("catalogue.tax.placeholder.code").text}
                />
              </label>

              <label className="catalogue-form-field">
                <span className="tax-field-required">{t("catalogue.tax.field.name_en").text} *</span>
                <input
                  id="tax-category-name-en-input"
                  type="text"
                  maxLength={80}
                  required
                  value={categoryNameEn}
                  onChange={(e) => setCategoryNameEn(e.target.value)}
                  placeholder={t("catalogue.tax.placeholder.name_en").text}
                />
              </label>

              <label className="catalogue-form-field">
                <span>{t("catalogue.tax.field.name_si").text}</span>
                <input
                  id="tax-category-name-si-input"
                  type="text"
                  maxLength={80}
                  value={categoryNameSi}
                  onChange={(e) => setCategoryNameSi(e.target.value)}
                />
              </label>

              <label className="catalogue-form-field">
                <span>{t("catalogue.tax.field.name_ta").text}</span>
                <input
                  id="tax-category-name-ta-input"
                  type="text"
                  maxLength={80}
                  value={categoryNameTa}
                  onChange={(e) => setCategoryNameTa(e.target.value)}
                />
              </label>

              <div className="tax-button-container">
                <button
                  id="submit-tax-category-button"
                  type="submit"
                  disabled={defineCategoryMutation.isPending || !categoryCode.trim() || !categoryNameEn.trim()}
                >
                  {defineCategoryMutation.isPending ? t("catalogue.loading").text : t("catalogue.tax.submit.category").text}
                </button>
              </div>

              {defineCategoryMutation.isError && (
                <p role="alert" className="tax-error-text">
                  {errorText(defineCategoryMutation.error, t("catalogue.error.generic").text)}
                </p>
              )}
            </form>
          </section>

          {/* Publish Tax Rate Card */}
          <section className="modern-table-card tax-card">
            <h2 className="tax-card-title">
              {t("catalogue.tax.publish_rate").text}
            </h2>
            <form onSubmit={handlePublishRateSubmit} className="catalogue-form-row">
              <label className="catalogue-form-field">
                <span className="tax-field-required">{t("catalogue.tax.field.category").text} *</span>
                <select
                  id="publish-rate-category-select"
                  required
                  value={rateTaxCategoryId}
                  onChange={(e) => setRateTaxCategoryId(e.target.value)}
                >
                  <option value="">{t("catalogue.field.choose").text}</option>
                  {categories.map((cat: TaxCategory) => (
                    <option key={cat.taxCategoryId} value={cat.taxCategoryId}>
                      {cat.code} - {cat.nameEn}
                    </option>
                  ))}
                </select>
              </label>

              <label className="catalogue-form-field">
                <span className="tax-field-required">{t("catalogue.tax.field.rate").text} *</span>
                <input
                  id="publish-rate-percent-input"
                  type="number"
                  step="0.01"
                  min="0"
                  max="100"
                  required
                  value={ratePercent}
                  onChange={(e) => setRatePercent(e.target.value)}
                />
              </label>

              <label className="catalogue-form-field">
                <span className="tax-field-required">{t("catalogue.tax.field.apply_from").text} *</span>
                <input
                  id="publish-rate-apply-from-input"
                  type="date"
                  required
                  min={todayIso}
                  value={applyFrom}
                  onChange={(e) => setApplyFrom(e.target.value)}
                />
              </label>

              <div className="tax-button-container">
                <button
                  id="submit-tax-rate-button"
                  type="submit"
                  disabled={publishRateMutation.isPending || !rateTaxCategoryId || !ratePercent || !applyFrom}
                >
                  {publishRateMutation.isPending ? t("catalogue.loading").text : t("catalogue.tax.submit.rate").text}
                </button>
              </div>

              {publishRateMutation.isError && (
                <p role="alert" className="tax-error-text">
                  {errorText(publishRateMutation.error, t("catalogue.error.generic").text)}
                </p>
              )}
            </form>
          </section>
        </div>
      )}

      {/* Tax Categories Table */}
      <section className="catalogue-section">
        <h2>{t("catalogue.tax.categories").text}</h2>
        <p className="tax-muted-text">
          {t("catalogue.tax.categories.description").text}
        </p>

        {categoriesQuery.isLoading && <p>{t("catalogue.loading").text}</p>}

        {categories.length === 0 && !categoriesQuery.isLoading && (
          <p>{t("catalogue.tax.no_categories").text}</p>
        )}

        {categories.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("catalogue.tax.column.code").text}</th>
                    <th>{t("catalogue.tax.column.name").text}</th>
                    <th>{t("catalogue.tax.column.name_si").text}</th>
                    <th>{t("catalogue.tax.column.name_ta").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {categories.map((cat: TaxCategory) => (
                    <tr
                      key={cat.taxCategoryId}
                      onClick={() => setSelectedCategoryId(selectedCategoryId === cat.taxCategoryId ? "" : cat.taxCategoryId)}
                      className={selectedCategoryId === cat.taxCategoryId ? "tax-category-row tax-category-row--selected" : "tax-category-row"}
                    >
                      <td className="tax-code-cell">{cat.code}</td>
                      <td>{cat.nameEn}</td>
                      <td>{cat.nameSi ?? "—"}</td>
                      <td>{cat.nameTa ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      {/* Tax Rates Table */}
      <section className="catalogue-section">
        <div className="tax-filter-header">
          <div>
            <h2>{t("catalogue.tax.rates").text}</h2>
            <p className="tax-muted-text">
              {t("catalogue.tax.rates.description").text}
            </p>
          </div>

          <label className="modern-field modern-field--select">
            <span className="modern-select">
              <select
                id="filter-tax-rates-category-select"
                value={selectedCategoryId}
                onChange={(e) => setSelectedCategoryId(e.target.value)}
              >
                <option value="">{t("catalogue.tax.filter.all_categories").text}</option>
                {categories.map((cat: TaxCategory) => (
                  <option key={cat.taxCategoryId} value={cat.taxCategoryId}>
                    {cat.code} - {cat.nameEn}
                  </option>
                ))}
              </select>
            </span>
          </label>
        </div>

        {ratesQuery.isLoading && <p>{t("catalogue.loading").text}</p>}

        {rates.length === 0 && !ratesQuery.isLoading && (
          <p>{t("catalogue.tax.no_rates").text}</p>
        )}

        {rates.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("catalogue.tax.column.code").text}</th>
                    <th>{t("catalogue.tax.column.rate").text}</th>
                    <th>{t("catalogue.tax.column.effective_from").text}</th>
                    <th>{t("catalogue.tax.column.effective_to").text}</th>
                    <th>{t("catalogue.tax.column.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {rates.map((rate: TaxRate, index: number) => {
                    const status = getRateStatus(rate);
                    return (
                      <tr key={`${rate.taxCategoryId}-${rate.effectiveFrom}-${index}`}>
                        <td className="tax-code-cell">{rate.taxCategoryCode ?? rate.taxCategoryId}</td>
                        <td className="tax-rate-cell">{Number(rate.ratePercent).toFixed(2)} %</td>
                        <td>{formatDate(rate.effectiveFrom)}</td>
                        <td>{rate.effectiveTo ? formatDate(rate.effectiveTo) : t("catalogue.tax.open_ended").text}</td>
                        <td>
                          <StateChip state={status.state} label={status.label} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}
