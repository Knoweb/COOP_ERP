import { useState, type FormEvent } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { useFormatDate } from "../../shell/i18n/formats";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import {
  useReportingApi,
  type ShopTargetPerformanceItem,
  type CreateShopTargetRequest,
  type UpdateShopTargetRequest
} from "./reportingApi";
import { errorText } from "./reportView";
import { TargetsSelect, type TargetsSelectOption } from "./TargetsSelect";
import { TargetsDatePicker } from "./TargetsDatePicker";
import "./reporting.css";

export function ShopTargetsPage() {
  const t = useT();
  const intl = useIntl();
  const formatDate = useFormatDate();
  const api = useReportingApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const canManage = useHasPermission("rpt.target.manage");

  // Filter state
  const [filterLocation, setFilterLocation] = useState<string>("");
  const [filterFrom, setFilterFrom] = useState<string>("");
  const [filterTo, setFilterTo] = useState<string>("");

  // Modal & form state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<ShopTargetPerformanceItem | null>(null);
  const [formLocationId, setFormLocationId] = useState<string>("");
  const [formPeriodStart, setFormPeriodStart] = useState<string>("");
  const [formPeriodEnd, setFormPeriodEnd] = useState<string>("");
  const [formTargetAmount, setFormTargetAmount] = useState<string>("");
  const [formError, setFormError] = useState<string | null>(null);

  // Queries
  const locationsQuery = useQuery({
    queryKey: ["reporting", "locations"],
    queryFn: () => api.locations(),
    staleTime: Infinity
  });

  const queryParams = {
    locationId: filterLocation || undefined,
    from: filterFrom || undefined,
    to: filterTo || undefined
  };

  const performanceQuery = useQuery({
    queryKey: ["reporting", "shop-targets-performance", filterLocation, filterFrom, filterTo],
    queryFn: () => api.getShopTargetPerformance(queryParams)
  });

  const items = performanceQuery.data?.items ?? [];
  const locations = locationsQuery.data ?? [];

  // Summary calculations
  let totalTarget = 0;
  let totalActual = 0;
  for (const item of items) {
    totalTarget += parseFloat(item.targetAmount) || 0;
    totalActual += parseFloat(item.actualAmount) || 0;
  }
  const totalVariance = totalActual - totalTarget;
  const overallAchievement = totalTarget > 0 ? ((totalActual / totalTarget) * 100).toFixed(1) : null;

  // Mutations
  const createMutation = useMutation({
    mutationFn: (request: CreateShopTargetRequest) => api.createShopTarget(request, key.current()),
    onSuccess: () => {
      key.next();
      setModalOpen(false);
      resetForm();
      queryClient.invalidateQueries({ queryKey: ["reporting", "shop-targets-performance"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
        setFormError(error.problem.title || t("reporting.error.generic").text);
      } else {
        setFormError(t("reporting.error.generic").text);
      }
    }
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, request }: { id: string; request: UpdateShopTargetRequest }) =>
      api.updateShopTarget(id, request, key.current()),
    onSuccess: () => {
      key.next();
      setModalOpen(false);
      resetForm();
      queryClient.invalidateQueries({ queryKey: ["reporting", "shop-targets-performance"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
        setFormError(error.problem.title || t("reporting.error.generic").text);
      } else {
        setFormError(t("reporting.error.generic").text);
      }
    }
  });

  function resetForm() {
    setEditingItem(null);
    setFormLocationId("");
    setFormPeriodStart("");
    setFormPeriodEnd("");
    setFormTargetAmount("");
    setFormError(null);
  }

  function handleOpenCreate() {
    resetForm();
    if (locations.length > 0) {
      setFormLocationId(locations[0].locationId);
    }
    setModalOpen(true);
  }

  function handleOpenEdit(item: ShopTargetPerformanceItem) {
    setEditingItem(item);
    setFormLocationId(item.locationId);
    setFormPeriodStart(item.periodStart);
    setFormPeriodEnd(item.periodEnd);
    setFormTargetAmount(item.targetAmount);
    setFormError(null);
    setModalOpen(true);
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);

    const amountNum = parseFloat(formTargetAmount);
    if (isNaN(amountNum) || amountNum < 0) {
      setFormError(t("reporting.error.generic").text);
      return;
    }

    if (!formPeriodStart || !formPeriodEnd || formPeriodStart > formPeriodEnd) {
      setFormError(t("reporting.error.generic").text);
      return;
    }

    if (editingItem) {
      updateMutation.mutate({
        id: editingItem.targetId,
        request: {
          targetAmount: amountNum.toFixed(2),
          periodStart: formPeriodStart,
          periodEnd: formPeriodEnd
        }
      });
    } else {
      createMutation.mutate({
        locationId: formLocationId,
        periodStart: formPeriodStart,
        periodEnd: formPeriodEnd,
        targetAmount: amountNum.toFixed(2),
        currency: "LKR"
      });
    }
  }

  const filterLocationOptions: TargetsSelectOption<string>[] = [
    { value: "", label: t("reporting.field.location.all").text },
    ...locations.map((loc) => ({
      value: loc.locationId,
      label: locationText(loc, intl.locale)
    }))
  ];

  const modalLocationOptions: TargetsSelectOption<string>[] = locations.map((loc) => ({
    value: loc.locationId,
    label: locationText(loc, intl.locale)
  }));

  const varianceTone = totalVariance >= 0 ? "success" : "alert";

  return (
    <main className="shell-page">
      <PageHeader
        icon="targets"
        title={t("reporting.targets.title").text}
      />

      <p className="sales-targets-subtitle">{t("reporting.targets.subtitle").text}</p>

      {/* Filter & Action Panel */}
      <section className="modern-filter-panel sales-targets-filter-panel">
        <div className="sales-targets-filter-controls">
          <div className="sales-targets-field sales-targets-field--location">
            <label className="sales-targets-field__label" htmlFor="filter-location">
              {t("reporting.field.location").text}
            </label>
            <TargetsSelect
              id="filter-location"
              aria-label={t("reporting.field.location").text}
              value={filterLocation}
              options={filterLocationOptions}
              onChange={setFilterLocation}
            />
          </div>

          <div className="sales-targets-field">
            <label className="sales-targets-field__label" htmlFor="filter-from">
              {t("reporting.field.from").text}
            </label>
            <TargetsDatePicker
              id="filter-from"
              label={t("reporting.field.from").text}
              value={filterFrom}
              onChange={setFilterFrom}
            />
          </div>

          <div className="sales-targets-field">
            <label className="sales-targets-field__label" htmlFor="filter-to">
              {t("reporting.field.to").text}
            </label>
            <TargetsDatePicker
              id="filter-to"
              label={t("reporting.field.to").text}
              value={filterTo}
              onChange={setFilterTo}
            />
          </div>
        </div>

        {canManage && (
          <div className="sales-targets-filter-action">
            <button
              type="button"
              className="btn btn-primary sales-targets-create-btn"
              onClick={handleOpenCreate}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              <span>{t("reporting.targets.create").text}</span>
            </button>
          </div>
        )}
      </section>

      {performanceQuery.isLoading && (
        <p className="sales-targets-state">{t("reporting.loading").text}</p>
      )}
      {performanceQuery.isError && (
        <p role="alert" className="reporting-error">
          {errorText(performanceQuery.error, t("reporting.error.generic").text)}
        </p>
      )}

      {/* KPI Cards */}
      {performanceQuery.isSuccess && (
        <>
          <div className="sales-targets-kpi-grid">
            <div className="sales-targets-kpi-card sales-targets-kpi-card--primary">
              <div className="sales-targets-kpi-card__top">
                <span className="sales-targets-kpi-label">
                  {t("reporting.targets.kpi.total_target").text}
                </span>
                <span className="sales-targets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <circle cx="12" cy="12" r="6" />
                    <circle cx="12" cy="12" r="2" />
                  </svg>
                </span>
              </div>
              <div className="sales-targets-kpi-card__bottom">
                <span className="sales-targets-kpi-value">
                  <MoneyDisplay amount={totalTarget.toFixed(2)} />
                </span>
              </div>
            </div>

            <div className="sales-targets-kpi-card sales-targets-kpi-card--navy">
              <div className="sales-targets-kpi-card__top">
                <span className="sales-targets-kpi-label">
                  {t("reporting.targets.kpi.total_actual").text}
                </span>
                <span className="sales-targets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
                    <polyline points="17 6 23 6 23 12" />
                  </svg>
                </span>
              </div>
              <div className="sales-targets-kpi-card__bottom">
                <span className="sales-targets-kpi-value">
                  <MoneyDisplay amount={totalActual.toFixed(2)} />
                </span>
              </div>
            </div>

            <div className={`sales-targets-kpi-card sales-targets-kpi-card--${varianceTone}`}>
              <div className="sales-targets-kpi-card__top">
                <span className="sales-targets-kpi-label">
                  {t("reporting.targets.kpi.total_variance").text}
                </span>
                <span className="sales-targets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M12 2v20" />
                    <path d="m17 5-5-3-5 3" />
                    <path d="m17 19-5 3-5-3" />
                  </svg>
                </span>
              </div>
              <div className="sales-targets-kpi-card__bottom">
                <span
                  className={`sales-targets-kpi-value ${
                    totalVariance >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"
                  }`}
                >
                  <MoneyDisplay amount={totalVariance.toFixed(2)} />
                </span>
              </div>
            </div>

            <div className="sales-targets-kpi-card sales-targets-kpi-card--lavender">
              <div className="sales-targets-kpi-card__top">
                <span className="sales-targets-kpi-label">
                  {t("reporting.targets.kpi.overall_achievement").text}
                </span>
                <span className="sales-targets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <line x1="19" y1="5" x2="5" y2="19" />
                    <circle cx="6.5" cy="6.5" r="2.5" />
                    <circle cx="17.5" cy="17.5" r="2.5" />
                  </svg>
                </span>
              </div>
              <div className="sales-targets-kpi-card__bottom">
                <span className="sales-targets-kpi-value">
                  {overallAchievement !== null ? `${overallAchievement}%` : "—"}
                </span>
              </div>
            </div>
          </div>

          {/* Comparison Section */}
          <section className="sales-targets-section">
            <div className="sales-targets-section__header">
              <div className="sales-targets-section__title-group">
                <span className="sales-targets-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <circle cx="12" cy="12" r="6" />
                    <circle cx="12" cy="12" r="2" />
                  </svg>
                </span>
                <h2>{t("reporting.targets.nav").text}</h2>
              </div>
              <span className="sales-targets-count-pill">{items.length}</span>
            </div>

            {items.length === 0 ? (
              <div className="sales-targets-empty-box">
                <span className="sales-targets-empty-box__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <circle cx="12" cy="12" r="6" />
                    <circle cx="12" cy="12" r="2" />
                  </svg>
                </span>
                <p>{t("reporting.targets.empty").text}</p>
              </div>
            ) : (
              <div className="modern-table-card sales-targets-table-card">
                <div className="modern-table-scroll">
                  <table
                    className="modern-table reporting-data-table sales-targets-table"
                    aria-label={t("reporting.targets.nav").text}
                  >
                    <thead>
                      <tr>
                        <th scope="col">{t("reporting.targets.col.shop").text}</th>
                        <th scope="col">{t("reporting.targets.col.period").text}</th>
                        <th scope="col" className="sales-targets-num">{t("reporting.targets.col.target").text}</th>
                        <th scope="col" className="sales-targets-num">{t("reporting.targets.col.actual").text}</th>
                        <th scope="col" className="sales-targets-num">{t("reporting.targets.col.variance").text}</th>
                        <th scope="col" className="sales-targets-num">{t("reporting.targets.col.achievement").text}</th>
                        <th scope="col">{t("reporting.targets.col.status").text}</th>
                        {canManage && <th scope="col">{t("reporting.exceptions.col.action").text}</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {items.map((item) => {
                        const targetVal = parseFloat(item.targetAmount) || 0;
                        const actualVal = parseFloat(item.actualAmount) || 0;
                        const varianceVal = parseFloat(item.varianceAmount) || 0;
                        const isMet = targetVal > 0 && actualVal >= targetVal;
                        const stateType = targetVal === 0 ? "draft" : isMet ? "issued" : "alert";
                        const statusLabel =
                          targetVal === 0
                            ? t("reporting.targets.status.not_set").text
                            : isMet
                              ? t("reporting.targets.status.achieved").text
                              : t("reporting.targets.status.behind").text;

                        return (
                          <tr key={item.targetId}>
                            <td className="sales-targets-shop-cell">
                              <span className="sales-targets-shop-name">{item.locationName}</span>
                            </td>
                            <td className="sales-targets-period-cell">
                              {formatDate(item.periodStart)} — {formatDate(item.periodEnd)}
                            </td>
                            <td className="sales-targets-num">
                              <MoneyDisplay amount={item.targetAmount} />
                            </td>
                            <td className="sales-targets-num">
                              <MoneyDisplay amount={item.actualAmount} />
                            </td>
                            <td
                              className={`sales-targets-num ${
                                varianceVal >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"
                              }`}
                            >
                              <MoneyDisplay amount={item.varianceAmount} />
                            </td>
                            <td className="sales-targets-num">
                              {item.achievementPercent !== null && item.achievementPercent !== undefined
                                ? `${item.achievementPercent}%`
                                : "—"}
                            </td>
                            <td>
                              <StateChip state={stateType} label={statusLabel} />
                            </td>
                            {canManage && (
                              <td className="targets-actions-cell">
                                <button
                                  type="button"
                                  className="sales-targets-edit-btn"
                                  onClick={() => handleOpenEdit(item)}
                                >
                                  <svg viewBox="0 0 24 24" aria-hidden="true">
                                    <path d="M12 20h9" />
                                    <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                                  </svg>
                                  <span>{t("reporting.targets.edit").text}</span>
                                </button>
                              </td>
                            )}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </section>
        </>
      )}

      {/* Create / Edit Modal */}
      {modalOpen && (
        <div
          className="sales-targets-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="sales-targets-modal-title"
        >
          <div className="sales-targets-modal">
            <div className="sales-targets-modal__header">
              <div className="sales-targets-modal__title-group">
                <span className="sales-targets-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <circle cx="12" cy="12" r="6" />
                    <circle cx="12" cy="12" r="2" />
                  </svg>
                </span>
                <h2 id="sales-targets-modal-title">
                  {editingItem ? t("reporting.targets.edit").text : t("reporting.targets.create").text}
                </h2>
              </div>
              <button
                type="button"
                className="sales-targets-modal__close"
                aria-label={t("reporting.targets.form.cancel").text}
                onClick={() => {
                  setModalOpen(false);
                  resetForm();
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>

            {formError && (
              <p role="alert" className="sales-targets-form-error">
                {formError}
              </p>
            )}

            <form onSubmit={handleSubmit} className="sales-targets-modal__form">
              {!editingItem && (
                <div className="sales-targets-form-group">
                  <label htmlFor="target-location">{t("reporting.targets.form.shop").text}</label>
                  <TargetsSelect
                    id="target-location"
                    aria-label={t("reporting.targets.form.shop").text}
                    value={formLocationId}
                    options={modalLocationOptions}
                    onChange={setFormLocationId}
                    portal
                    required
                  />
                </div>
              )}

              <div className="sales-targets-form-row">
                <div className="sales-targets-form-group">
                  <label htmlFor="target-start">{t("reporting.targets.form.period_start").text}</label>
                  <TargetsDatePicker
                    id="target-start"
                    label={t("reporting.targets.form.period_start").text}
                    value={formPeriodStart}
                    onChange={setFormPeriodStart}
                    portal
                  />
                </div>

                <div className="sales-targets-form-group">
                  <label htmlFor="target-end">{t("reporting.targets.form.period_end").text}</label>
                  <TargetsDatePicker
                    id="target-end"
                    label={t("reporting.targets.form.period_end").text}
                    value={formPeriodEnd}
                    onChange={setFormPeriodEnd}
                    portal
                  />
                </div>
              </div>

              <div className="sales-targets-form-group">
                <label htmlFor="target-amount">{t("reporting.targets.form.target_amount").text}</label>
                <input
                  id="target-amount"
                  type="number"
                  min="0"
                  step="0.01"
                  className="form-control"
                  value={formTargetAmount}
                  onChange={(e) => setFormTargetAmount(e.target.value)}
                  required
                />
              </div>

              <div className="sales-targets-form-actions">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => {
                    setModalOpen(false);
                    resetForm();
                  }}
                >
                  {t("reporting.targets.form.cancel").text}
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={createMutation.isPending || updateMutation.isPending}
                >
                  {t("reporting.targets.form.save").text}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </main>
  );
}
