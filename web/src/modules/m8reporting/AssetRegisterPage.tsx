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
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import {
  useReportingApi,
  type Asset,
  type RegisterAssetRequest
} from "./reportingApi";
import { AssetSelect, type AssetSelectOption } from "./AssetSelect";
import { AssetDatePicker } from "./AssetDatePicker";
import "./reporting.css";

type AssetType = "LAND" | "BUILDING" | "VEHICLE" | "EQUIPMENT" | "FURNITURE" | "OTHER";

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
  const [formType, setFormType] = useState<AssetType>("EQUIPMENT");
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

  const filterLocationOptions: AssetSelectOption<string>[] = [
    { value: "", label: t("reporting.assets.filter.all_locations").text },
    ...locations.map((loc) => ({
      value: loc.locationId,
      label: locationText(loc, intl.locale)
    }))
  ];

  const assetTypeChoices: AssetSelectOption<AssetType>[] = [
    { value: "LAND", label: t("reporting.assets.type.land").text },
    { value: "BUILDING", label: t("reporting.assets.type.building").text },
    { value: "VEHICLE", label: t("reporting.assets.type.vehicle").text },
    { value: "EQUIPMENT", label: t("reporting.assets.type.equipment").text },
    { value: "FURNITURE", label: t("reporting.assets.type.furniture").text },
    { value: "OTHER", label: t("reporting.assets.type.other").text }
  ];

  const filterTypeOptions: AssetSelectOption<string>[] = [
    { value: "", label: t("reporting.assets.filter.all_types").text },
    ...assetTypeChoices
  ];

  const filterStatusOptions: AssetSelectOption<string>[] = [
    { value: "", label: t("reporting.assets.filter.all_statuses").text },
    { value: "ACTIVE", label: t("reporting.assets.status.active").text },
    { value: "MAINTENANCE", label: t("reporting.assets.status.maintenance").text },
    { value: "DISPOSED", label: t("reporting.assets.status.disposed").text },
    { value: "INACTIVE", label: t("reporting.assets.status.inactive").text }
  ];

  const modalLocationOptions: AssetSelectOption<string>[] = [
    { value: "", label: t("reporting.assets.modal.location.none").text },
    ...locations.map((loc) => ({
      value: loc.locationId,
      label: locationText(loc, intl.locale)
    }))
  ];

  function resolveAssetTypeLabel(type: string): string {
    switch (type) {
      case "LAND":
        return t("reporting.assets.type.land").text;
      case "BUILDING":
        return t("reporting.assets.type.building").text;
      case "VEHICLE":
        return t("reporting.assets.type.vehicle").text;
      case "EQUIPMENT":
        return t("reporting.assets.type.equipment").text;
      case "FURNITURE":
        return t("reporting.assets.type.furniture").text;
      case "OTHER":
        return t("reporting.assets.type.other").text;
      default:
        return type;
    }
  }

  function resolveAssetStatus(status: string): { chip: ChipState; label: string } {
    switch (status) {
      case "ACTIVE":
        return { chip: "issued", label: t("reporting.assets.status.active").text };
      case "MAINTENANCE":
        return { chip: "disputed", label: t("reporting.assets.status.maintenance").text };
      case "DISPOSED":
        return { chip: "void", label: t("reporting.assets.status.disposed").text };
      case "INACTIVE":
        return { chip: "alert", label: t("reporting.assets.status.inactive").text };
      default:
        return { chip: "alert", label: status };
    }
  }

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
      <p className="assets-subtitle">{t("reporting.assets.subtitle").text}</p>

      {/* Filter and Action Bar */}
      <section className="modern-filter-panel assets-filter-panel">
        <div className="assets-filter-controls">
          <div className="assets-field assets-field--location">
            <label className="assets-field__label" htmlFor="filter-location">
              {t("reporting.field.location").text}
            </label>
            <AssetSelect
              id="filter-location"
              aria-label={t("reporting.field.location").text}
              value={filterLocationId}
              options={filterLocationOptions}
              onChange={setFilterLocationId}
            />
          </div>

          <div className="assets-field">
            <label className="assets-field__label" htmlFor="filter-type">
              {t("reporting.assets.col.type").text}
            </label>
            <AssetSelect
              id="filter-type"
              aria-label={t("reporting.assets.col.type").text}
              value={filterType}
              options={filterTypeOptions}
              onChange={setFilterType}
            />
          </div>

          <div className="assets-field">
            <label className="assets-field__label" htmlFor="filter-status">
              {t("reporting.assets.col.status").text}
            </label>
            <AssetSelect
              id="filter-status"
              aria-label={t("reporting.assets.col.status").text}
              value={filterStatus}
              options={filterStatusOptions}
              onChange={setFilterStatus}
            />
          </div>
        </div>

        <div className="assets-filter-actions">
          <button
            type="button"
            className="btn btn-secondary assets-export-btn"
            onClick={exportCsv}
            disabled={assets.length === 0}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>{t("reporting.assets.btn.export").text}</span>
          </button>

          {canManage && (
            <button
              type="button"
              className="btn btn-primary assets-register-btn"
              onClick={() => {
                resetForm();
                setModalOpen(true);
              }}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              <span>{t("reporting.assets.btn.register").text}</span>
            </button>
          )}
        </div>
      </section>

      {/* Loading & Error States */}
      {assetsQuery.isLoading && <p className="assets-state">{t("reporting.loading").text}</p>}
      {assetsQuery.isError && (
        <p role="alert" className="reporting-error">
          {t("reporting.error.generic").text}
        </p>
      )}

      {/* Content */}
      {!assetsQuery.isLoading && !assetsQuery.isError && (
        <>
          {/* Summary KPIs */}
          <div className="assets-kpi-grid">
            <div className="assets-kpi-card assets-kpi-card--primary">
              <div className="assets-kpi-card__top">
                <span className="assets-kpi-label">{t("reporting.assets.kpi.total").text}</span>
                <span className="assets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
                    <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
                    <line x1="12" y1="22.08" x2="12" y2="12" />
                  </svg>
                </span>
              </div>
              <div className="assets-kpi-card__bottom">
                <span className="assets-kpi-value">{totalAssets}</span>
              </div>
            </div>

            <div className="assets-kpi-card assets-kpi-card--navy">
              <div className="assets-kpi-card__top">
                <span className="assets-kpi-label">{t("reporting.assets.kpi.cost").text}</span>
                <span className="assets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <rect x="2" y="5" width="20" height="14" rx="2" />
                    <line x1="2" y1="10" x2="22" y2="10" />
                  </svg>
                </span>
              </div>
              <div className="assets-kpi-card__bottom">
                <span className="assets-kpi-value">
                  <MoneyDisplay amount={totalAcquisitionCost.toFixed(2)} />
                </span>
              </div>
            </div>

            <div className="assets-kpi-card assets-kpi-card--success">
              <div className="assets-kpi-card__top">
                <span className="assets-kpi-label">{t("reporting.assets.kpi.active").text}</span>
                <span className="assets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <polyline points="16 9 10.5 15 8 12.5" />
                  </svg>
                </span>
              </div>
              <div className="assets-kpi-card__bottom">
                <span className="assets-kpi-value">{activeAssets}</span>
              </div>
            </div>

            <div className="assets-kpi-card assets-kpi-card--lavender">
              <div className="assets-kpi-card__top">
                <span className="assets-kpi-label">{t("reporting.assets.kpi.branches").text}</span>
                <span className="assets-kpi-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
                    <circle cx="12" cy="10" r="3" />
                  </svg>
                </span>
              </div>
              <div className="assets-kpi-card__bottom">
                <span className="assets-kpi-value">{locationCount}</span>
              </div>
            </div>
          </div>

          {/* Section + Table / Empty State */}
          <section className="assets-section">
            <div className="assets-section__header">
              <div className="assets-section__title-group">
                <span className="assets-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M3 21h18" />
                    <path d="M5 21V7l8-4v18" />
                    <path d="M19 21V11l-6-4" />
                  </svg>
                </span>
                <h2>{t("reporting.assets.nav").text}</h2>
              </div>
              <span className="assets-count-pill">{totalAssets}</span>
            </div>

            {assets.length === 0 ? (
              <div className="assets-empty-box">
                <span className="assets-empty-box__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
                  </svg>
                </span>
                <p>{t("reporting.assets.empty").text}</p>
              </div>
            ) : (
              <div className="modern-table-card assets-table-card">
                <div className="modern-table-scroll">
                  <table
                    className="modern-table reporting-data-table assets-table"
                    aria-label={t("reporting.assets.nav").text}
                  >
                    <thead>
                      <tr>
                        <th scope="col">{t("reporting.assets.col.code").text}</th>
                        <th scope="col">{t("reporting.assets.col.name").text}</th>
                        <th scope="col">{t("reporting.assets.col.type").text}</th>
                        <th scope="col">{t("reporting.assets.col.location").text}</th>
                        <th scope="col">{t("reporting.assets.col.date").text}</th>
                        <th scope="col" className="assets-num">{t("reporting.assets.col.cost").text}</th>
                        <th scope="col">{t("reporting.assets.col.status").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {assets.map((asset) => {
                        const { chip, label } = resolveAssetStatus(asset.status);
                        return (
                          <tr key={asset.assetId}>
                            <td>
                              <span className="assets-code-pill">{asset.assetCode}</span>
                            </td>
                            <td>
                              <div className="assets-name-cell">{asset.name}</div>
                              {asset.description && (
                                <div className="assets-desc-sub">{asset.description}</div>
                              )}
                            </td>
                            <td>
                              <span className="assets-type-pill">{resolveAssetTypeLabel(asset.assetType)}</span>
                            </td>
                            <td>
                              {asset.locationName ? (
                                asset.locationName
                              ) : (
                                <span className="assets-muted-cell">
                                  {t("reporting.assets.location.entity_wide").text}
                                </span>
                              )}
                            </td>
                            <td className="assets-date-cell">
                              {asset.acquisitionDate ? formatDate(asset.acquisitionDate) : "—"}
                            </td>
                            <td className="assets-num">
                              {asset.acquisitionCost ? (
                                <MoneyDisplay amount={asset.acquisitionCost} />
                              ) : (
                                "—"
                              )}
                            </td>
                            <td>
                              <StateChip state={chip} label={label} />
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
        </>
      )}

      {/* Modal Dialog for Registration */}
      {modalOpen && (
        <div
          className="assets-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="assets-modal-title"
        >
          <div className="assets-modal">
            <div className="assets-modal__header">
              <div className="assets-modal__title-group">
                <span className="assets-section__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M12 5v14M5 12h14" />
                  </svg>
                </span>
                <h2 id="assets-modal-title">{t("reporting.assets.modal.title").text}</h2>
              </div>
              <button
                type="button"
                className="assets-modal__close"
                aria-label={t("reporting.assets.modal.cancel").text}
                onClick={() => setModalOpen(false)}
                disabled={registerMutation.isPending}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>

            {formError && (
              <div role="alert" className="assets-form-error">
                {formError}
              </div>
            )}

            <form onSubmit={handleRegister} className="assets-modal__form">
              <div className="assets-form-group">
                <label htmlFor="form-asset-code">{t("reporting.assets.modal.code").text}</label>
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

              <div className="assets-form-group">
                <label htmlFor="form-asset-name">{t("reporting.assets.modal.name").text}</label>
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

              <div className="assets-form-group">
                <label htmlFor="form-asset-desc">{t("reporting.assets.modal.description").text}</label>
                <textarea
                  id="form-asset-desc"
                  className="input"
                  rows={2}
                  value={formDescription}
                  onChange={(e) => setFormDescription(e.target.value)}
                />
              </div>

              <div className="assets-form-group">
                <label htmlFor="form-asset-type">{t("reporting.assets.modal.type").text}</label>
                <AssetSelect
                  id="form-asset-type"
                  aria-label={t("reporting.assets.modal.type").text}
                  value={formType}
                  options={assetTypeChoices}
                  onChange={setFormType}
                  portal
                />
              </div>

              <div className="assets-form-group">
                <label htmlFor="form-asset-location">{t("reporting.assets.modal.location").text}</label>
                <AssetSelect
                  id="form-asset-location"
                  aria-label={t("reporting.assets.modal.location").text}
                  value={formLocationId}
                  options={modalLocationOptions}
                  onChange={setFormLocationId}
                  portal
                />
              </div>

              <div className="assets-form-row">
                <div className="assets-form-group">
                  <label htmlFor="form-asset-date">{t("reporting.assets.modal.date").text}</label>
                  <AssetDatePicker
                    id="form-asset-date"
                    label={t("reporting.assets.modal.date").text}
                    value={formDate}
                    onChange={setFormDate}
                    portal
                  />
                </div>

                <div className="assets-form-group">
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

              <div className="assets-form-actions">
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
