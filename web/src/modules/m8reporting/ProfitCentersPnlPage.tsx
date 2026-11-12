import { useState, type FormEvent } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { businessToday } from "../../shell/i18n/formats";
import { locationText } from "../../shell/i18n/localName";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import {
  useReportingApi,
  type CreateProfitCenterRequest,
  type ProfitCenterPnlItem
} from "./reportingApi";
import { PnlSelect, type PnlSelectOption } from "./PnlSelect";
import { PnlMonthPicker } from "./PnlMonthPicker";
import "./reporting.css";

type ProfitCenterCategory = "GENERAL" | "RETAIL" | "AGENCY" | "PACKING";

export function ProfitCentersPnlPage() {
  const t = useT();
  const intl = useIntl();
  const api = useReportingApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const canManage = useHasPermission("rpt.profit_center.manage");

  const todayStr = businessToday();
  const currentMonthStr = todayStr.slice(0, 7);

  // Filters
  const [filterMonth, setFilterMonth] = useState<string>(currentMonthStr);
  const [filterProfitCenterId, setFilterProfitCenterId] = useState<string>("");

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [formCode, setFormCode] = useState("");
  const [formName, setFormName] = useState("");
  const [formCategory, setFormCategory] = useState<ProfitCenterCategory>("AGENCY");
  const [formLocationId, setFormLocationId] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  // Queries
  const profitCentersQuery = useQuery({
    queryKey: ["reporting", "profit-centers"],
    queryFn: () => api.listProfitCenters()
  });

  const locationsQuery = useQuery({
    queryKey: ["reporting", "locations"],
    queryFn: () => api.locations(),
    staleTime: Infinity
  });

  const pnlQuery = useQuery({
    queryKey: ["reporting", "profit-centers-pnl", filterMonth, filterProfitCenterId],
    queryFn: () =>
      api.monthlyProfitCenterPnl({
        month: filterMonth || undefined,
        profitCenterId: filterProfitCenterId || undefined
      })
  });

  // Mutations
  const createMutation = useMutation({
    mutationFn: (request: CreateProfitCenterRequest) => api.createProfitCenter(request, key.current()),
    onSuccess: () => {
      key.next();
      setModalOpen(false);
      resetForm();
      queryClient.invalidateQueries({ queryKey: ["reporting", "profit-centers"] });
      queryClient.invalidateQueries({ queryKey: ["reporting", "profit-centers-pnl"] });
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
    setFormCode("");
    setFormName("");
    setFormCategory("AGENCY");
    setFormLocationId("");
    setFormError(null);
  }

  function handleCreate(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!formCode.trim() || !formName.trim()) {
      setFormError(t("reporting.error.generic").text);
      return;
    }
    createMutation.mutate({
      code: formCode.trim(),
      name: formName.trim(),
      category: formCategory,
      locationId: formLocationId || undefined
    });
  }

  const report = pnlQuery.data;
  const summary = report?.summary;
  const items = report?.items ?? [];
  const unallocated = report?.unallocated;
  const profitCenters = profitCentersQuery.data ?? [];
  const locations = locationsQuery.data ?? [];

  const profitCenterOptions: PnlSelectOption<string>[] = [
    { value: "", label: t("reporting.profit_centers.all_centers").text },
    ...profitCenters.map((pc) => ({
      value: pc.profitCenterId,
      label: `${pc.code} — ${pc.name}`
    }))
  ];

  const categoryOptions: PnlSelectOption<ProfitCenterCategory>[] = [
    { value: "AGENCY", label: t("reporting.profit_centers.modal.category.agency").text },
    { value: "PACKING", label: t("reporting.profit_centers.modal.category.packing").text },
    { value: "RETAIL", label: t("reporting.profit_centers.modal.category.retail").text },
    { value: "GENERAL", label: t("reporting.profit_centers.modal.category.wholesale").text }
  ];

  const locationOptions: PnlSelectOption<string>[] = [
    { value: "", label: t("reporting.profit_centers.modal.location.none").text },
    ...locations.map((loc) => ({
      value: loc.locationId,
      label: locationText(loc, intl.locale)
    }))
  ];

  const profitVal = parseFloat(summary?.grossProfit ?? "0");
  const isSummaryLoss = Boolean(summary?.grossProfit && profitVal < 0);
  const profitTone = isSummaryLoss ? "alert" : "success";
  const statusTone = summary?.costPending ? "alert" : "success";
  const rowCount = items.length + (unallocated ? 1 : 0);

  return (
    <main className="shell-page">
      <PageHeader
        icon="society"
        title={t("reporting.profit_centers.title").text}
      />
      <p className="pnl-subtitle">{t("reporting.profit_centers.subtitle").text}</p>

      {/* Filter and Action Bar */}
      <section className="modern-filter-panel pnl-filter-panel">
        <div className="pnl-filter-controls">
          <div className="pnl-field">
            <label className="pnl-field__label" htmlFor="filter-month">
              {t("reporting.profit_centers.month").text}
            </label>
            <PnlMonthPicker
              id="filter-month"
              label={t("reporting.profit_centers.month").text}
              value={filterMonth}
              onChange={setFilterMonth}
            />
          </div>

          <div className="pnl-field pnl-field--center">
            <label className="pnl-field__label" htmlFor="filter-center">
              {t("reporting.profit_centers.center").text}
            </label>
            <PnlSelect
              id="filter-center"
              aria-label={t("reporting.profit_centers.center").text}
              value={filterProfitCenterId}
              options={profitCenterOptions}
              onChange={setFilterProfitCenterId}
            />
          </div>
        </div>

        {canManage && (
          <div className="pnl-filter-action">
            <button
              type="button"
              className="btn btn-primary pnl-new-btn"
              onClick={() => {
                resetForm();
                setModalOpen(true);
              }}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              <span>{t("reporting.profit_centers.btn_new").text}</span>
            </button>
          </div>
        )}
      </section>

      {/* Loading & Error States */}
      {pnlQuery.isLoading && <p className="pnl-state">{t("reporting.loading").text}</p>}
      {pnlQuery.isError && (
        <p role="alert" className="reporting-error">
          {t("reporting.error.generic").text}
        </p>
      )}

      {/* Report Content */}
      {report && summary && (
        <>
          {/* Summary KPIs */}
          <div className="pnl-kpi-grid">
            <div className="pnl-kpi-card pnl-kpi-card--primary">
              <div className="pnl-kpi-card__top">
                <span className="pnl-kpi-label">{t("reporting.profit_centers.kpi.revenue").text}</span>
                <span className="pnl-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
                    <polyline points="17 6 23 6 23 12" />
                  </svg>
                </span>
              </div>
              <div className="pnl-kpi-card__bottom">
                <span className="pnl-kpi-value">
                  <MoneyDisplay amount={summary.totalRevenue} />
                </span>
              </div>
            </div>

            <div className="pnl-kpi-card pnl-kpi-card--navy">
              <div className="pnl-kpi-card__top">
                <span className="pnl-kpi-label">{t("reporting.profit_centers.kpi.cost").text}</span>
                <span className="pnl-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M4 2v20l4-2 4 2 4-2 4 2V2l-4 2-4-2-4 2z" />
                    <path d="M8 10h8" />
                    <path d="M8 14h5" />
                  </svg>
                </span>
              </div>
              <div className="pnl-kpi-card__bottom">
                <span className="pnl-kpi-value">
                  {summary.costPending ? "—" : <MoneyDisplay amount={summary.totalCost} />}
                </span>
              </div>
            </div>

            <div className={`pnl-kpi-card pnl-kpi-card--${profitTone}`}>
              <div className="pnl-kpi-card__top">
                <span className="pnl-kpi-label">{t("reporting.profit_centers.kpi.profit").text}</span>
                <span className="pnl-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <rect x="2" y="5" width="20" height="14" rx="2" />
                    <line x1="2" y1="10" x2="22" y2="10" />
                  </svg>
                </span>
              </div>
              <div className="pnl-kpi-card__bottom">
                <span
                  className={`pnl-kpi-value ${
                    isSummaryLoss ? "targets-kpi-negative" : "targets-kpi-positive"
                  }`}
                >
                  {summary.costPending ? "—" : <MoneyDisplay amount={summary.grossProfit} />}
                </span>
              </div>
            </div>

            <div className="pnl-kpi-card pnl-kpi-card--lavender">
              <div className="pnl-kpi-card__top">
                <span className="pnl-kpi-label">{t("reporting.profit_centers.kpi.margin").text}</span>
                <span className="pnl-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <line x1="19" y1="5" x2="5" y2="19" />
                    <circle cx="6.5" cy="6.5" r="2.5" />
                    <circle cx="17.5" cy="17.5" r="2.5" />
                  </svg>
                </span>
              </div>
              <div className="pnl-kpi-card__bottom">
                <span className="pnl-kpi-value">
                  {summary.marginPercent != null ? `${summary.marginPercent}%` : "—"}
                </span>
              </div>
            </div>

            <div className={`pnl-kpi-card pnl-kpi-card--${statusTone}`}>
              <div className="pnl-kpi-card__top">
                <span className="pnl-kpi-label">{t("reporting.profit_centers.col.status").text}</span>
                <span className="pnl-kpi-badge" aria-hidden="true">
                  {summary.costPending ? (
                    <svg viewBox="0 0 24 24" focusable="false">
                      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                      <line x1="12" y1="9" x2="12" y2="13" />
                      <line x1="12" y1="17" x2="12.01" y2="17" />
                    </svg>
                  ) : (
                    <svg viewBox="0 0 24 24" focusable="false">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="16 9 10.5 15 8 12.5" />
                    </svg>
                  )}
                </span>
              </div>
              <div className="pnl-kpi-card__bottom pnl-kpi-card__bottom--chip">
                {summary.costPending ? (
                  <StateChip state="alert" label={t("reporting.profit_centers.status.pending").text} />
                ) : (
                  <StateChip state="issued" label={t("reporting.profit_centers.status.settled").text} />
                )}
              </div>
            </div>
          </div>

          {/* Pending Cost Warning Banner */}
          {summary.costPending && (
            <div role="alert" className="pnl-pending-banner">
              <span className="pnl-pending-banner__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                  <line x1="12" y1="9" x2="12" y2="13" />
                  <line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
              </span>
              <div>
                <strong>{t("reporting.profit_centers.status.pending").text}: </strong>
                <span>{t("reporting.profit_centers.banner.pending").text}</span>
              </div>
            </div>
          )}

          {/* Agency Comparison Section */}
          <section className="pnl-section">
            <div className="pnl-section__header">
              <div className="pnl-section__title-group">
                <span className="pnl-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M3 21h18" />
                    <path d="M5 21V7l8-4v18" />
                    <path d="M19 21V11l-6-4" />
                    <path d="M9 9v.01" />
                    <path d="M9 13v.01" />
                    <path d="M9 17v.01" />
                  </svg>
                </span>
                <h2>{t("reporting.profit_centers.nav").text}</h2>
              </div>
              <span className="pnl-count-pill">{rowCount}</span>
            </div>

            <div className="modern-table-card pnl-table-card">
              <div className="modern-table-scroll">
                <table
                  className="modern-table reporting-data-table pnl-table"
                  aria-label={t("reporting.profit_centers.nav").text}
                >
                  <thead>
                    <tr>
                      <th scope="col">{t("reporting.profit_centers.col.code").text}</th>
                      <th scope="col">{t("reporting.profit_centers.col.name").text}</th>
                      <th scope="col">{t("reporting.profit_centers.col.category").text}</th>
                      <th scope="col">{t("reporting.profit_centers.col.location").text}</th>
                      <th scope="col" className="pnl-num">{t("reporting.profit_centers.col.txns").text}</th>
                      <th scope="col" className="pnl-num">{t("reporting.profit_centers.col.revenue").text}</th>
                      <th scope="col" className="pnl-num">{t("reporting.profit_centers.col.cost").text}</th>
                      <th scope="col" className="pnl-num">{t("reporting.profit_centers.col.profit").text}</th>
                      <th scope="col" className="pnl-num">{t("reporting.profit_centers.col.margin").text}</th>
                      <th scope="col" className="pnl-center">{t("reporting.profit_centers.col.status").text}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.length === 0 && !unallocated && (
                      <tr>
                        <td colSpan={10} className="pnl-empty-cell">
                          {t("reporting.profit_centers.empty").text}
                        </td>
                      </tr>
                    )}
                    {items.map((item) => renderRow(item))}
                    {unallocated && renderUnallocatedRow(unallocated)}
                  </tbody>
                  <tfoot>
                    <tr className="pnl-total-row">
                      <td colSpan={4}>{t("reporting.profit_centers.combined_total").text}</td>
                      <td className="pnl-num">
                        {items.reduce((acc, i) => acc + i.txnCount, 0) + (unallocated?.txnCount ?? 0)}
                      </td>
                      <td className="pnl-num">
                        <MoneyDisplay amount={summary.totalRevenue} />
                      </td>
                      <td className="pnl-num">
                        {summary.costPending ? "—" : <MoneyDisplay amount={summary.totalCost} />}
                      </td>
                      <td
                        className={`pnl-num ${
                          isSummaryLoss ? "targets-kpi-negative" : "targets-kpi-positive"
                        }`}
                      >
                        {summary.costPending ? "—" : <MoneyDisplay amount={summary.grossProfit} />}
                      </td>
                      <td className="pnl-num">
                        {summary.marginPercent != null ? `${summary.marginPercent}%` : "—"}
                      </td>
                      <td className="pnl-center">
                        {summary.costPending ? (
                          <StateChip state="alert" label={t("reporting.profit_centers.status.pending").text} />
                        ) : (
                          <StateChip state="issued" label={t("reporting.profit_centers.status.settled").text} />
                        )}
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </div>
          </section>
        </>
      )}

      {/* Creation Modal */}
      {modalOpen && (
        <div className="pnl-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="pnl-modal-title">
          <div className="pnl-modal">
            <div className="pnl-modal__header">
              <div className="pnl-modal__title-group">
                <span className="pnl-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M12 5v14M5 12h14" />
                  </svg>
                </span>
                <h2 id="pnl-modal-title">{t("reporting.profit_centers.modal.title").text}</h2>
              </div>
              <button
                type="button"
                className="pnl-modal__close"
                aria-label={t("reporting.profit_centers.modal.cancel").text}
                onClick={() => setModalOpen(false)}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>

            {formError && <p role="alert" className="reporting-error">{formError}</p>}

            <form onSubmit={handleCreate} className="pnl-modal__form">
              <div className="pnl-form-group">
                <label htmlFor="form-code">{t("reporting.profit_centers.modal.code").text}</label>
                <input
                  id="form-code"
                  type="text"
                  className="input"
                  required
                  value={formCode}
                  onChange={(e) => setFormCode(e.target.value)}
                />
              </div>

              <div className="pnl-form-group">
                <label htmlFor="form-name">{t("reporting.profit_centers.modal.name").text}</label>
                <input
                  id="form-name"
                  type="text"
                  className="input"
                  required
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                />
              </div>

              <div className="pnl-form-group">
                <label htmlFor="form-category">{t("reporting.profit_centers.modal.category").text}</label>
                <PnlSelect
                  id="form-category"
                  aria-label={t("reporting.profit_centers.modal.category").text}
                  value={formCategory}
                  options={categoryOptions}
                  onChange={setFormCategory}
                  portal
                />
              </div>

              <div className="pnl-form-group">
                <label htmlFor="form-location">{t("reporting.profit_centers.modal.location").text}</label>
                <PnlSelect
                  id="form-location"
                  aria-label={t("reporting.profit_centers.modal.location").text}
                  value={formLocationId}
                  options={locationOptions}
                  onChange={setFormLocationId}
                  portal
                />
              </div>

              <div className="pnl-form-actions">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setModalOpen(false)}
                >
                  {t("reporting.profit_centers.modal.cancel").text}
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={createMutation.isPending}
                >
                  {t("reporting.profit_centers.modal.save").text}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </main>
  );

  function renderRow(item: ProfitCenterPnlItem) {
    const isLoss = item.grossProfit && parseFloat(item.grossProfit) < 0;
    return (
      <tr key={item.profitCenterId}>
        <td>
          <span className="pnl-code-pill">{item.code}</span>
        </td>
        <td className="pnl-name-cell">{item.name}</td>
        <td>
          <span className="pnl-category-pill">{item.category}</span>
        </td>
        <td className="pnl-muted-cell">{item.locationName ?? "—"}</td>
        <td className="pnl-num">{item.txnCount}</td>
        <td className="pnl-num">
          <MoneyDisplay amount={item.revenue} />
        </td>
        <td className="pnl-num">
          {item.costPending ? "—" : <MoneyDisplay amount={item.cost} />}
        </td>
        <td className={`pnl-num ${isLoss ? "targets-kpi-negative" : "targets-kpi-positive"}`}>
          {item.costPending ? "—" : <MoneyDisplay amount={item.grossProfit} />}
        </td>
        <td className="pnl-num">
          {item.marginPercent != null ? `${item.marginPercent}%` : "—"}
        </td>
        <td className="pnl-center">
          {item.costPending ? (
            <StateChip state="alert" label={t("reporting.profit_centers.status.pending").text} />
          ) : (
            <StateChip state="issued" label={t("reporting.profit_centers.status.settled").text} />
          )}
        </td>
      </tr>
    );
  }

  function renderUnallocatedRow(unalloc: ProfitCenterPnlItem) {
    const isLoss = unalloc.grossProfit && parseFloat(unalloc.grossProfit) < 0;
    return (
      <tr key="unallocated-row" className="pnl-unallocated-row">
        <td>
          <span className="pnl-code-pill pnl-code-pill--unallocated">{unalloc.code}</span>
        </td>
        <td>{t("reporting.profit_centers.unallocated").text}</td>
        <td className="pnl-muted-cell">—</td>
        <td className="pnl-muted-cell">—</td>
        <td className="pnl-num">{unalloc.txnCount}</td>
        <td className="pnl-num">
          <MoneyDisplay amount={unalloc.revenue} />
        </td>
        <td className="pnl-num">
          {unalloc.costPending ? "—" : <MoneyDisplay amount={unalloc.cost} />}
        </td>
        <td className={`pnl-num ${isLoss ? "targets-kpi-negative" : "targets-kpi-positive"}`}>
          {unalloc.costPending ? "—" : <MoneyDisplay amount={unalloc.grossProfit} />}
        </td>
        <td className="pnl-num">
          {unalloc.marginPercent != null ? `${unalloc.marginPercent}%` : "—"}
        </td>
        <td className="pnl-center">
          {unalloc.costPending ? (
            <StateChip state="alert" label={t("reporting.profit_centers.status.pending").text} />
          ) : (
            <StateChip state="issued" label={t("reporting.profit_centers.status.settled").text} />
          )}
        </td>
      </tr>
    );
  }
}
