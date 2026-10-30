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
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import {
  useReportingApi,
  type Asset,
  type RegisterAssetRequest
} from "./reportingApi";
import "./reporting.css";

export function AssetRegisterPage() {
  const t = useT();
  const intl = useIntl();
  const formatDate = useFormatDate();
  const api = useReportingApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const canManage = useHasPermission("rpt.asset.manage");

  // Filters
  const [filterLocationId, setFilterLocationId] = useState<string>("");
  const [filterType, setFilterType] = useState<string>("");
  const [filterStatus, setFilterStatus] = useState<string>("");

  // Modal State
  const [modalOpen, setModalOpen] = useState(false);
  const [formCode, setFormCode] = useState("");
  const [formName, setFormName] = useState("");
  const [formDescription, setFormDescription] = useState("");
  const [formType, setFormType] = useState<"LAND" | "BUILDING" | "VEHICLE" | "EQUIPMENT" | "FURNITURE" | "OTHER">("EQUIPMENT");
  const [formLocationId, setFormLocationId] = useState("");
  const [formDate, setFormDate] = useState("");
  const [formCost, setFormCost] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  // Queries
  const locationsQuery = useQuery({
    queryKey: ["reporting", "locations"],
    queryFn: () => api.locations(),
    staleTime: Infinity
  });

  const assetsQuery = useQuery({
    queryKey: ["reporting", "assets", filterLocationId, filterType, filterStatus],
    queryFn: () =>
      api.listAssets({
        locationId: filterLocationId || undefined,
        assetType: filterType || undefined,
        status: filterStatus || undefined
      })
  });

  // Mutation
  const registerMutation = useMutation({
    mutationFn: (request: RegisterAssetRequest) => api.registerAsset(request, key.current()),
    onSuccess: () => {
      key.next();
      setModalOpen(false);
      resetForm();
      queryClient.invalidateQueries({ queryKey: ["reporting", "assets"] });
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
    setFormDescription("");
    setFormType("EQUIPMENT");
    setFormLocationId("");
    setFormDate("");
    setFormCost("");
    setFormError(null);
  }

  function handleRegister(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!formCode.trim() || !formName.trim()) {
      setFormError(t("reporting.error.generic").text);
      return;
    }
    registerMutation.mutate({
      assetCode: formCode.trim(),
      name: formName.trim(),
      description: formDescription.trim() || undefined,
      assetType: formType,
      locationId: formLocationId || undefined,
      acquisitionDate: formDate || undefined,
      acquisitionCost: formCost ? parseFloat(formCost).toFixed(2) : undefined
    });
  }

  const assets: Asset[] = assetsQuery.data ?? [];
  const locations = locationsQuery.data ?? [];

  // Summary Metrics
  const totalAssets = assets.length;
  const activeAssets = assets.filter((a) => a.status === "ACTIVE").length;
  const totalAcquisitionCost = assets
    .filter((a) => a.acquisitionCost)
    .reduce((sum, a) => sum + parseFloat(a.acquisitionCost!), 0);
  const locationCount = new Set(assets.map((a) => a.locationId).filter(Boolean)).size;

  function exportCsv() {
    if (assets.length === 0) return;
    const headers = [
      t("reporting.assets.col.code").text,
      t("reporting.assets.col.name").text,
      t("reporting.assets.col.type").text,
      t("reporting.assets.col.location").text,
      t("reporting.assets.col.date").text,
      t("reporting.assets.col.cost").text,
      t("reporting.assets.col.status").text
    ];
    const rows = assets.map((a) => [
      `"${a.assetCode.replace(/"/g, '""')}"`,
      `"${a.name.replace(/"/g, '""')}"`,
      `"${a.assetType}"`,
      `"${(a.locationName || t("reporting.assets.location.entity_wide").text).replace(/"/g, '""')}"`,
      `"${a.acquisitionDate || ""}"`,
      `"${a.acquisitionCost || ""}"`,
      `"${a.status}"`
    ]);
    const csvContent = [headers.join(","), ...rows.map((r) => r.join(","))].join("\n");
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `asset-register-${businessToday()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main className="shell-page">
      <PageHeader
        icon="society"
        title={t("reporting.assets.title").text}
      />
      <p className="shop-targets-card__subtitle">{t("reporting.assets.subtitle").text}</p>

      {/* Filter and Action Bar */}
      <div className="reporting-filter-bar" style={{ justifyContent: "space-between" }}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--space-2)", alignItems: "flex-end" }}>
          <div>
            <label className="reporting-filter-label" htmlFor="filter-location">
              {t("reporting.field.location").text}
            </label>
            <select
              id="filter-location"
              className="input"
              value={filterLocationId}
              onChange={(e) => setFilterLocationId(e.target.value)}
            >
              <option value="">{t("reporting.assets.filter.all_locations").text}</option>
              {locations.map((loc) => (
                <option key={loc.locationId} value={loc.locationId}>
                  {locationText(loc, intl.locale)}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="reporting-filter-label" htmlFor="filter-type">
              {t("reporting.assets.col.type").text}
            </label>
            <select
              id="filter-type"
              className="input"
              value={filterType}
              onChange={(e) => setFilterType(e.target.value)}
            >
              <option value="">{t("reporting.assets.filter.all_types").text}</option>
              <option value="LAND">{t("reporting.assets.type.land").text}</option>
              <option value="BUILDING">{t("reporting.assets.type.building").text}</option>
              <option value="VEHICLE">{t("reporting.assets.type.vehicle").text}</option>
              <option value="EQUIPMENT">{t("reporting.assets.type.equipment").text}</option>
              <option value="FURNITURE">{t("reporting.assets.type.furniture").text}</option>
              <option value="OTHER">{t("reporting.assets.type.other").text}</option>
            </select>
          </div>

          <div>
            <label className="reporting-filter-label" htmlFor="filter-status">
              {t("reporting.assets.col.status").text}
            </label>
            <select
              id="filter-status"
              className="input"
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
            >
              <option value="">{t("reporting.assets.filter.all_statuses").text}</option>
              <option value="ACTIVE">{t("reporting.assets.status.active").text}</option>
              <option value="MAINTENANCE">{t("reporting.assets.status.maintenance").text}</option>
              <option value="DISPOSED">{t("reporting.assets.status.disposed").text}</option>
              <option value="INACTIVE">{t("reporting.assets.status.inactive").text}</option>
            </select>
          </div>
        </div>

        <div style={{ display: "flex", gap: "var(--space-2)" }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={exportCsv}
            disabled={assets.length === 0}
          >
            {t("reporting.assets.btn.export").text}
          </button>
          {canManage && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                resetForm();
                setModalOpen(true);
              }}
            >
              {t("reporting.assets.btn.register").text}
            </button>
          )}
        </div>
      </div>

      {/* Loading & Error States */}
      {assetsQuery.isLoading && <p className="reporting-loading">{t("reporting.loading").text}</p>}
      {assetsQuery.isError && <p className="reporting-error">{t("reporting.error.generic").text}</p>}

      {/* Content */}
      {!assetsQuery.isLoading && !assetsQuery.isError && (
        <div className="shop-targets-card">
          {/* Summary KPIs */}
          <div className="targets-kpi-grid">
            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.assets.kpi.total").text}</span>
              <span className="targets-kpi-value">{totalAssets}</span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.assets.kpi.cost").text}</span>
              <span className="targets-kpi-value">
                <MoneyDisplay amount={totalAcquisitionCost.toFixed(2)} />
              </span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.assets.kpi.active").text}</span>
              <span className="targets-kpi-value">{activeAssets}</span>
            </div>

            <div className="targets-kpi-card">
              <span className="targets-kpi-label">{t("reporting.assets.kpi.branches").text}</span>
              <span className="targets-kpi-value">{locationCount}</span>
            </div>
          </div>

          {/* Table */}
          {assets.length === 0 ? (
            <p className="reporting-empty">{t("reporting.assets.empty").text}</p>
          ) : (
            <div style={{ overflowX: "auto" }}>
              <table className="table reporting-data-table">
                <thead>
                  <tr>
                    <th>{t("reporting.assets.col.code").text}</th>
                    <th>{t("reporting.assets.col.name").text}</th>
                    <th>{t("reporting.assets.col.type").text}</th>
                    <th>{t("reporting.assets.col.location").text}</th>
                    <th>{t("reporting.assets.col.date").text}</th>
                    <th style={{ textAlign: "right" }}>{t("reporting.assets.col.cost").text}</th>
                    <th>{t("reporting.assets.col.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {assets.map((asset) => (
                    <tr key={asset.assetId}>
                      <td style={{ fontWeight: "var(--font-weight-strong)" }}>{asset.assetCode}</td>
                      <td>
                        <div>{asset.name}</div>
                        {asset.description && (
                          <div style={{ fontSize: "var(--font-size-sm)", color: "var(--color-text-muted)" }}>
                            {asset.description}
                          </div>
                        )}
                      </td>
                      <td>
                        <span className="badge">{asset.assetType}</span>
                      </td>
                      <td>
                        {asset.locationName ? (
                          asset.locationName
                        ) : (
                          <span style={{ color: "var(--color-text-muted)" }}>
                            {t("reporting.assets.location.entity_wide").text}
                          </span>
                        )}
                      </td>
                      <td>{asset.acquisitionDate ? formatDate(asset.acquisitionDate) : "—"}</td>
                      <td style={{ textAlign: "right" }}>
                        {asset.acquisitionCost ? (
                          <MoneyDisplay amount={asset.acquisitionCost} />
                        ) : (
                          "—"
                        )}
                      </td>
                      <td>
                        <StateChip
                          state={asset.status === "ACTIVE" ? "issued" : "alert"}
                          label={asset.status}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Modal Dialog for Registration */}
      {modalOpen && (
        <div className="targets-modal-backdrop" role="dialog" aria-modal="true">
          <div className="targets-modal">
            <h2 style={{ marginTop: 0, marginBottom: "var(--space-3)" }}>
              {t("reporting.assets.modal.title").text}
            </h2>

            {formError && (
              <div
                style={{
                  color: "var(--color-alert-text)",
                  backgroundColor: "var(--color-surface-subtle)",
                  padding: "var(--space-2)",
                  borderRadius: "var(--radius)",
                  marginBottom: "var(--space-2)"
                }}
              >
                {formError}
              </div>
            )}

            <form onSubmit={handleRegister}>
              <div className="targets-form-group">
                <label htmlFor="form-asset-code">{t("reporting.assets.modal.code").text} *</label>
                <input
                  id="form-asset-code"
                  type="text"
                  maxLength={32}
                  className="input"
                  required
                  value={formCode}
                  onChange={(e) => setFormCode(e.target.value)}
                  placeholder={t("reporting.assets.placeholder.code").text}
                />
              </div>

              <div className="targets-form-group">
                <label htmlFor="form-asset-name">{t("reporting.assets.modal.name").text} *</label>
                <input
                  id="form-asset-name"
                  type="text"
                  className="input"
                  required
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder={t("reporting.assets.placeholder.name").text}
                />
              </div>

              <div className="targets-form-group">
                <label htmlFor="form-asset-desc">{t("reporting.assets.modal.description").text}</label>
                <textarea
                  id="form-asset-desc"
                  className="input"
                  rows={2}
                  value={formDescription}
                  onChange={(e) => setFormDescription(e.target.value)}
                />
              </div>

              <div className="targets-form-group">
                <label htmlFor="form-asset-type">{t("reporting.assets.modal.type").text} *</label>
                <select
                  id="form-asset-type"
                  className="input"
                  value={formType}
                  onChange={(e) =>
                    setFormType(
                      e.target.value as "LAND" | "BUILDING" | "VEHICLE" | "EQUIPMENT" | "FURNITURE" | "OTHER"
                    )
                  }
                >
                  <option value="LAND">{t("reporting.assets.type.land").text}</option>
                  <option value="BUILDING">{t("reporting.assets.type.building").text}</option>
                  <option value="VEHICLE">{t("reporting.assets.type.vehicle").text}</option>
                  <option value="EQUIPMENT">{t("reporting.assets.type.equipment").text}</option>
                  <option value="FURNITURE">{t("reporting.assets.type.furniture").text}</option>
                  <option value="OTHER">{t("reporting.assets.type.other").text}</option>
                </select>
              </div>

              <div className="targets-form-group">
                <label htmlFor="form-asset-location">{t("reporting.assets.modal.location").text}</label>
                <select
                  id="form-asset-location"
                  className="input"
                  value={formLocationId}
                  onChange={(e) => setFormLocationId(e.target.value)}
                >
                  <option value="">{t("reporting.assets.modal.location.none").text}</option>
                  {locations.map((loc) => (
                    <option key={loc.locationId} value={loc.locationId}>
                      {locationText(loc, intl.locale)}
                    </option>
                  ))}
                </select>
              </div>

              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "var(--space-2)" }}>
                <div className="targets-form-group">
                  <label htmlFor="form-asset-date">{t("reporting.assets.modal.date").text}</label>
                  <input
                    id="form-asset-date"
                    type="date"
                    className="input"
                    value={formDate}
                    onChange={(e) => setFormDate(e.target.value)}
                  />
                </div>

                <div className="targets-form-group">
                  <label htmlFor="form-asset-cost">{t("reporting.assets.modal.cost").text}</label>
                  <input
                    id="form-asset-cost"
                    type="number"
                    step="0.01"
                    min="0"
                    className="input"
                    value={formCost}
                    onChange={(e) => setFormCost(e.target.value)}
                    placeholder="0.00"
                  />
                </div>
              </div>

              <div className="targets-form-actions">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setModalOpen(false)}
                  disabled={registerMutation.isPending}
                >
                  {t("reporting.assets.modal.cancel").text}
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={registerMutation.isPending}
                >
                  {registerMutation.isPending
                    ? t("reporting.loading").text
                    : t("reporting.assets.modal.save").text}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </main>
  );
}
