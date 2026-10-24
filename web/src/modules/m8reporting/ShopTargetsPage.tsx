import { useState, type FormEvent } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
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
import "./reporting.css";

export function ShopTargetsPage() {
  const t = useT();
  const intl = useIntl();
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
    if (locationsQuery.data && locationsQuery.data.length > 0) {
      setFormLocationId(locationsQuery.data[0].locationId);
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

    if (formPeriodStart > formPeriodEnd) {
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

  return (
    <main className="shell-page">
      <PageHeader
        icon="stock"
        title={t("reporting.targets.title").text}
        actions={
          canManage ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleOpenCreate}
            >
              {t("reporting.targets.create").text}
            </button>
          ) : undefined
        }
      />

      <p className="shop-targets-card__subtitle">{t("reporting.targets.subtitle").text}</p>

      {/* Filter bar */}
      <div className="reporting-filter-bar">
        <div>
          <label className="reporting-filter-label" htmlFor="filter-location">
            {t("reporting.field.location").text}
          </label>
          <select
            id="filter-location"
            className="form-control"
            value={filterLocation}
            onChange={(e) => setFilterLocation(e.target.value)}
          >
            <option value="">{t("reporting.field.location.all").text}</option>
            {locationsQuery.data?.map((loc) => (
              <option key={loc.locationId} value={loc.locationId}>
                {locationText(loc, intl.locale)}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="reporting-filter-label" htmlFor="filter-from">
            {t("reporting.field.from").text}
          </label>
          <input
            id="filter-from"
            type="date"
            className="form-control"
            value={filterFrom}
            onChange={(e) => setFilterFrom(e.target.value)}
          />
        </div>

        <div>
          <label className="reporting-filter-label" htmlFor="filter-to">
            {t("reporting.field.to").text}
          </label>
          <input
            id="filter-to"
            type="date"
            className="form-control"
            value={filterTo}
            onChange={(e) => setFilterTo(e.target.value)}
          />
        </div>
      </div>

      {performanceQuery.isLoading && <p>{t("reporting.loading").text}</p>}
      {performanceQuery.isError && (
        <p role="alert">{errorText(performanceQuery.error, t("reporting.error.generic").text)}</p>
      )}

      {/* KPI Cards */}
      {performanceQuery.isSuccess && (
        <div className="targets-kpi-grid">
          <div className="targets-kpi-card">
            <span className="targets-kpi-label">{t("reporting.targets.kpi.total_target").text}</span>
            <span className="targets-kpi-value"><MoneyDisplay amount={totalTarget.toFixed(2)} /></span>
          </div>
          <div className="targets-kpi-card">
            <span className="targets-kpi-label">{t("reporting.targets.kpi.total_actual").text}</span>
            <span className="targets-kpi-value"><MoneyDisplay amount={totalActual.toFixed(2)} /></span>
          </div>
          <div className="targets-kpi-card">
            <span className="targets-kpi-label">{t("reporting.targets.kpi.total_variance").text}</span>
            <span className={`targets-kpi-value ${totalVariance >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"}`}>
              <MoneyDisplay amount={totalVariance.toFixed(2)} />
            </span>
          </div>
          <div className="targets-kpi-card">
            <span className="targets-kpi-label">{t("reporting.targets.kpi.overall_achievement").text}</span>
            <span className="targets-kpi-value">
              {overallAchievement !== null ? `${overallAchievement}%` : "—"}
            </span>
          </div>
        </div>
      )}

      {/* Comparison Table */}
      {performanceQuery.isSuccess && items.length === 0 && (
        <p>{t("reporting.targets.empty").text}</p>
      )}

      {performanceQuery.isSuccess && items.length > 0 && (
        <table className="reporting-data-table">
          <thead>
            <tr>
              <th>{t("reporting.targets.col.shop").text}</th>
              <th>{t("reporting.targets.col.period").text}</th>
              <th>{t("reporting.targets.col.target").text}</th>
              <th>{t("reporting.targets.col.actual").text}</th>
              <th>{t("reporting.targets.col.variance").text}</th>
              <th>{t("reporting.targets.col.achievement").text}</th>
              <th>{t("reporting.targets.col.status").text}</th>
              {canManage && <th>{t("reporting.exceptions.col.action").text}</th>}
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
                  <td>{item.locationName}</td>
                  <td>
                    {item.periodStart} — {item.periodEnd}
                  </td>
                  <td>
                    <MoneyDisplay amount={item.targetAmount} />
                  </td>
                  <td>
                    <MoneyDisplay amount={item.actualAmount} />
                  </td>
                  <td className={varianceVal >= 0 ? "targets-kpi-positive" : "targets-kpi-negative"}>
                    <MoneyDisplay amount={item.varianceAmount} />
                  </td>
                  <td>
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
                        className="btn btn-secondary btn-sm"
                        onClick={() => handleOpenEdit(item)}
                      >
                        {t("reporting.targets.edit").text}
                      </button>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {/* Create / Edit Modal */}
      {modalOpen && (
        <div className="targets-modal-backdrop" role="dialog" aria-modal="true">
          <div className="targets-modal">
            <h2>{editingItem ? t("reporting.targets.edit").text : t("reporting.targets.create").text}</h2>
            {formError && <p role="alert" className="form-error">{formError}</p>}
            <form onSubmit={handleSubmit}>
              {!editingItem && (
                <div className="targets-form-group">
                  <label htmlFor="target-location">{t("reporting.targets.form.shop").text}</label>
                  <select
                    id="target-location"
                    className="form-control"
                    value={formLocationId}
                    onChange={(e) => setFormLocationId(e.target.value)}
                    required
                  >
                    {locationsQuery.data?.map((loc) => (
                      <option key={loc.locationId} value={loc.locationId}>
                        {locationText(loc, intl.locale)}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div className="targets-form-group">
                <label htmlFor="target-start">{t("reporting.targets.form.period_start").text}</label>
                <input
                  id="target-start"
                  type="date"
                  className="form-control"
                  value={formPeriodStart}
                  onChange={(e) => setFormPeriodStart(e.target.value)}
                  required
                />
              </div>

              <div className="targets-form-group">
                <label htmlFor="target-end">{t("reporting.targets.form.period_end").text}</label>
                <input
                  id="target-end"
                  type="date"
                  className="form-control"
                  value={formPeriodEnd}
                  onChange={(e) => setFormPeriodEnd(e.target.value)}
                  required
                />
              </div>

              <div className="targets-form-group">
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

              <div className="targets-form-actions">
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
