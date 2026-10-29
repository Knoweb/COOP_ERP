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
import "./reporting.css";

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
  const [formCategory, setFormCategory] = useState<"GENERAL" | "RETAIL" | "AGENCY" | "PACKING">("AGENCY");
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

  return (
    <main className="shell-page">
      <PageHeader
        icon="society"
        title={t("reporting.profit_centers.title").text}
      />
      <p className="shop-targets-card__subtitle">{t("reporting.profit_centers.subtitle").text}</p>

      {/* Filter and Action Bar */}
      <div className="reporting-filter-bar" style={{ justifyContent: "space-between" }}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--space-2)", alignItems: "flex-end" }}>
          <div>
            <label className="reporting-filter-label" htmlFor="filter-month">
              {t("reporting.profit_centers.month").text}
            </label>
            <input
              id="filter-month"
              type="month"
              className="input"
              value={filterMonth}
              onChange={(e) => setFilterMonth(e.target.value)}
            />
          </div>

          <div>
            <label className="reporting-filter-label" htmlFor="filter-center">
              {t("reporting.profit_centers.center").text}
            </label>
            <select
              id="filter-center"
              className="input"
              value={filterProfitCenterId}
              onChange={(e) => setFilterProfitCenterId(e.target.value)}
            >
              <option value="">{t("reporting.profit_centers.all_centers").text}</option>
              {profitCenters.map((pc) => (
                <option key={pc.profitCenterId} value={pc.profitCenterId}>
                  {pc.code} — {pc.name}
                </option>
              ))}
            </select>
          </div>
        </div>

        {canManage && (
          <div>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                resetForm();
                setModalOpen(true);
              }}
            >
              {t("reporting.profit_centers.btn_new").text}
            </button>
          </div>
        )}
      </div>

      {/* Loading & Error States */}
      {pnlQuery.isLoading && <p className="reporting-loading">{t("reporting.loading").text}</p>}
      {pnlQuery.isError && <p className="reporting-error">{t("reporting.error.generic").text}</p>}

      {/* Report Content */}
      {report && summary && (
        <div className="shop-targets-card">
          {/* Summary KPIs */}
          <div className="targets-kpi-grid">
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.profit_centers.kpi.revenue").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={summary.totalRevenue} />
              </span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.profit_centers.kpi.cost").text}</span>
              <span className="targets-kpi-value">
                {summary.costPending ? "—" : <MoneyDisplay amount={summary.totalCost} />}
              </span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.profit_centers.kpi.profit").text}</span>
              <span
                className={`targets-kpi-value ${
                  summary.grossProfit && parseFloat(summary.grossProfit) < 0
                    ? "targets-kpi-negative"
                    : "targets-kpi-positive"
                }`}
              >
                {summary.costPending ? "—" : <MoneyDisplay amount={summary.grossProfit} />}
              </span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.profit_centers.kpi.margin").text}</span>
              <span className="targets-kpi-value">
                {summary.marginPercent != null ? `${summary.marginPercent}%` : "—"}
              </span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.profit_centers.col.status").text}</span>
              <span className="targets-kpi-value">
                {summary.costPending ? (
                  <StateChip state="alert" label={t("reporting.profit_centers.status.pending").text} />
                ) : (
                  <StateChip state="issued" label={t("reporting.profit_centers.status.settled").text} />
                )}
              </span>
            </div>
          </div>

          {/* Pending Cost Warning Banner */}
          {summary.costPending && (
            <div role="alert" className="pnl-pending-banner">
              <strong>{t("reporting.profit_centers.status.pending").text}: </strong>
              {t("reporting.profit_centers.banner.pending").text}
            </div>
          )}

          {/* Agency Comparison Table */}
          <div style={{ overflowX: "auto" }}>
            <table className="reporting-data-table">
              <thead>
                <tr>
                  <th>{t("reporting.profit_centers.col.code").text}</th>
                  <th>{t("reporting.profit_centers.col.name").text}</th>
                  <th>{t("reporting.profit_centers.col.category").text}</th>
                  <th>{t("reporting.profit_centers.col.location").text}</th>
                  <th style={{ textAlign: "right" }}>{t("reporting.profit_centers.col.txns").text}</th>
                  <th style={{ textAlign: "right" }}>{t("reporting.profit_centers.col.revenue").text}</th>
                  <th style={{ textAlign: "right" }}>{t("reporting.profit_centers.col.cost").text}</th>
                  <th style={{ textAlign: "right" }}>{t("reporting.profit_centers.col.profit").text}</th>
                  <th style={{ textAlign: "right" }}>{t("reporting.profit_centers.col.margin").text}</th>
                  <th style={{ textAlign: "center" }}>{t("reporting.profit_centers.col.status").text}</th>
                </tr>
              </thead>
              <tbody>
                {items.length === 0 && !unallocated && (
                  <tr>
                    <td colSpan={10} style={{ textAlign: "center", color: "var(--color-text-muted)" }}>
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
                  <td style={{ textAlign: "right" }}>
                    {items.reduce((acc, i) => acc + i.txnCount, 0) + (unallocated?.txnCount ?? 0)}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    <MoneyDisplay amount={summary.totalRevenue} />
                  </td>
                  <td style={{ textAlign: "right" }}>
                    {summary.costPending ? "—" : <MoneyDisplay amount={summary.totalCost} />}
                  </td>
                  <td
                    style={{ textAlign: "right" }}
                    className={
                      summary.grossProfit && parseFloat(summary.grossProfit) < 0
                        ? "targets-kpi-negative"
                        : "targets-kpi-positive"
                    }
                  >
                    {summary.costPending ? "—" : <MoneyDisplay amount={summary.grossProfit} />}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    {summary.marginPercent != null ? `${summary.marginPercent}%` : "—"}
                  </td>
                  <td style={{ textAlign: "center" }}>
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
      )}

      {/* Creation Modal */}
      {modalOpen && (
        <div className="targets-modal-backdrop" role="dialog" aria-modal="true">
          <div className="targets-modal">
            <h2 style={{ marginTop: 0 }}>{t("reporting.profit_centers.modal.title").text}</h2>
            {formError && <p className="reporting-error">{formError}</p>}
            <form onSubmit={handleCreate}>
              <div className="targets-form-group">
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

              <div className="targets-form-group">
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

              <div className="targets-form-group">
                <label htmlFor="form-category">{t("reporting.profit_centers.modal.category").text}</label>
                <select
                  id="form-category"
                  className="input"
                  value={formCategory}
                  onChange={(e) => setFormCategory(e.target.value as "GENERAL" | "RETAIL" | "AGENCY" | "PACKING")}
                >
                  <option value="AGENCY">{t("reporting.profit_centers.modal.category.agency").text}</option>
                  <option value="PACKING">{t("reporting.profit_centers.modal.category.packing").text}</option>
                  <option value="RETAIL">{t("reporting.profit_centers.modal.category.retail").text}</option>
                  <option value="GENERAL">{t("reporting.profit_centers.modal.category.wholesale").text}</option>
                </select>
              </div>

              <div className="targets-form-group">
                <label htmlFor="form-location">{t("reporting.profit_centers.modal.location").text}</label>
                <select
                  id="form-location"
                  className="input"
                  value={formLocationId}
                  onChange={(e) => setFormLocationId(e.target.value)}
                >
                  <option value="">{t("reporting.profit_centers.modal.location.none").text}</option>
                  {locations.map((loc) => (
                    <option key={loc.locationId} value={loc.locationId}>
                      {locationText(loc, intl.locale)}
                    </option>
                  ))}
                </select>
              </div>

              <div className="targets-form-actions">
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
        <td><strong>{item.code}</strong></td>
        <td>{item.name}</td>
        <td>
          <span style={{ fontSize: "var(--font-size-sm)", color: "var(--color-text-muted)" }}>
            {item.category}
          </span>
        </td>
        <td>{item.locationName ?? "—"}</td>
        <td style={{ textAlign: "right" }}>{item.txnCount}</td>
        <td style={{ textAlign: "right" }}>
          <MoneyDisplay amount={item.revenue} />
        </td>
        <td style={{ textAlign: "right" }}>
          {item.costPending ? "—" : <MoneyDisplay amount={item.cost} />}
        </td>
        <td
          style={{ textAlign: "right" }}
          className={isLoss ? "targets-kpi-negative" : "targets-kpi-positive"}
        >
          {item.costPending ? "—" : <MoneyDisplay amount={item.grossProfit} />}
        </td>
        <td style={{ textAlign: "right" }}>
          {item.marginPercent != null ? `${item.marginPercent}%` : "—"}
        </td>
        <td style={{ textAlign: "center" }}>
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
      <tr key="unallocated-row" style={{ fontStyle: "italic", background: "var(--color-surface-subtle)" }}>
        <td><em>{unalloc.code}</em></td>
        <td>{t("reporting.profit_centers.unallocated").text}</td>
        <td>—</td>
        <td>—</td>
        <td style={{ textAlign: "right" }}>{unalloc.txnCount}</td>
        <td style={{ textAlign: "right" }}>
          <MoneyDisplay amount={unalloc.revenue} />
        </td>
        <td style={{ textAlign: "right" }}>
          {unalloc.costPending ? "—" : <MoneyDisplay amount={unalloc.cost} />}
        </td>
        <td
          style={{ textAlign: "right" }}
          className={isLoss ? "targets-kpi-negative" : "targets-kpi-positive"}
        >
          {unalloc.costPending ? "—" : <MoneyDisplay amount={unalloc.grossProfit} />}
        </td>
        <td style={{ textAlign: "right" }}>
          {unalloc.marginPercent != null ? `${unalloc.marginPercent}%` : "—"}
        </td>
        <td style={{ textAlign: "center" }}>
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

