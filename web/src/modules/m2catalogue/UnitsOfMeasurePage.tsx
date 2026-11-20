import { useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import {
  useCatalogueApi,
  type UomDetail
} from "./catalogueApi";
import { errorText } from "./skuView";

export function UnitsOfMeasurePage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canManage = useHasPermission("cat.uom.manage");

  const defineKey = useIdempotencyKey();
  const updateKey = useIdempotencyKey();

  const [includeInactive, setIncludeInactive] = useState(false);
  const [showDefineForm, setShowDefineForm] = useState(false);
  const [editingUom, setEditingUom] = useState<UomDetail | null>(null);

  // Define Form State
  const [code, setCode] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [nameSi, setNameSi] = useState("");
  const [nameTa, setNameTa] = useState("");
  const [description, setDescription] = useState("");
  const [isWeight, setIsWeight] = useState(false);

  // Edit Form State
  const [editNameEn, setEditNameEn] = useState("");
  const [editNameSi, setEditNameSi] = useState("");
  const [editNameTa, setEditNameTa] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editIsWeight, setEditIsWeight] = useState(false);
  const [editStatus, setEditStatus] = useState<"ACTIVE" | "INACTIVE">("ACTIVE");

  const [successMsg, setSuccessMsg] = useState("");
  const [errorMsg, setErrorMsg] = useState("");

  const uomsQuery = useQuery({
    queryKey: ["catalogue", "uoms", includeInactive],
    queryFn: () => api.listUoms(includeInactive)
  });

  const defineMutation = useMutation({
    mutationFn: () =>
      api.defineUom(
        {
          uomCode: code.trim().toUpperCase(),
          nameEn: nameEn.trim(),
          nameSi: nameSi.trim() || undefined,
          nameTa: nameTa.trim() || undefined,
          description: description.trim() || undefined,
          weight: isWeight
        },
        defineKey.current()
      ),
    onSuccess: () => {
      defineKey.next();
      setCode("");
      setNameEn("");
      setNameSi("");
      setNameTa("");
      setDescription("");
      setIsWeight(false);
      setShowDefineForm(false);
      setSuccessMsg(t("catalogue.uom.success.defined").text);
      setErrorMsg("");
      queryClient.invalidateQueries({ queryKey: ["catalogue", "uoms"] });
      queryClient.invalidateQueries({ queryKey: ["catalogue", "reference"] });
    },
    onError: (err) => {
      if (err instanceof ApiProblem) {
        defineKey.next();
      }
      setErrorMsg(errorText(err, t("catalogue.error.generic").text));
    }
  });

  const updateMutation = useMutation({
    mutationFn: () => {
      if (!editingUom) throw new Error("No UOM selected for edit");
      return api.updateUom(
        editingUom.uomCode,
        {
          nameEn: editNameEn.trim(),
          nameSi: editNameSi.trim() || undefined,
          nameTa: editNameTa.trim() || undefined,
          description: editDescription.trim() || undefined,
          weight: editIsWeight,
          status: editStatus
        },
        updateKey.current()
      );
    },
    onSuccess: () => {
      updateKey.next();
      setEditingUom(null);
      setSuccessMsg(t("catalogue.uom.success.updated").text);
      setErrorMsg("");
      queryClient.invalidateQueries({ queryKey: ["catalogue", "uoms"] });
      queryClient.invalidateQueries({ queryKey: ["catalogue", "reference"] });
    },
    onError: (err) => {
      if (err instanceof ApiProblem) {
        updateKey.next();
      }
      setErrorMsg(errorText(err, t("catalogue.error.generic").text));
    }
  });

  const handleDefineSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSuccessMsg("");
    setErrorMsg("");
    if (!code.trim() || !nameEn.trim()) return;
    defineMutation.mutate();
  };

  const handleUpdateSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSuccessMsg("");
    setErrorMsg("");
    if (!editNameEn.trim()) return;
    updateMutation.mutate();
  };

  const startEdit = (uom: UomDetail) => {
    setEditingUom(uom);
    setEditNameEn(uom.nameEn);
    setEditNameSi(uom.nameSi ?? "");
    setEditNameTa(uom.nameTa ?? "");
    setEditDescription(uom.description ?? "");
    setEditIsWeight(Boolean(uom.weight));
    setEditStatus((uom.status as "ACTIVE" | "INACTIVE") ?? "ACTIVE");
    setShowDefineForm(false);
    setSuccessMsg("");
    setErrorMsg("");
  };

  const cancelEdit = () => {
    setEditingUom(null);
    setErrorMsg("");
  };

  const uoms = uomsQuery.data ?? [];

  return (
    <div className="catalogue-uoms-page app-page-shell">
      <PageHeader
        title={t("catalogue.uom.title").text}
        icon="uom"
        actions={
          canManage && !showDefineForm && !editingUom ? (
            <button
              type="button"
              className="modern-btn modern-btn--primary"
              onClick={() => {
                setShowDefineForm(true);
                setSuccessMsg("");
                setErrorMsg("");
              }}
            >
              + {t("catalogue.uom.new_button").text}
            </button>
          ) : undefined
        }
      />

      <p className="catalogue-import-subtitle" style={{ marginBottom: "var(--space-2)" }}>
        {t("catalogue.uom.subtitle").text}
      </p>

      {successMsg && (
        <div className="alert alert-success" role="status" style={{ marginBottom: "var(--space-2)" }}>
          {successMsg}
        </div>
      )}

      {errorMsg && (
        <div className="alert alert-danger" role="alert" style={{ marginBottom: "var(--space-2)" }}>
          {errorMsg}
        </div>
      )}

      {/* Define Form Card */}
      {showDefineForm && (
        <div className="catalogue-department-card">
          <div className="catalogue-department-card__header">
            <div className="catalogue-department-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21.3 15.3a2.4 2.4 0 0 1 0 3.4l-2.6 2.6a2.4 2.4 0 0 1-3.4 0L2.7 8.7a2.41 2.41 0 0 1 0-3.4l2.6-2.6a2.41 2.41 0 0 1 3.4 0Z" />
                <path d="m14.5 12.5 2-2" />
                <path d="m11.5 9.5 2-2" />
                <path d="m8.5 6.5 2-2" />
                <path d="m17.5 15.5 2-2" />
              </svg>
            </div>
            <h3 className="catalogue-department-card__title">{t("catalogue.uom.define_title").text}</h3>
          </div>
          <form onSubmit={handleDefineSubmit} className="catalogue-form-grid">
            <label className="catalogue-form-field">
              <span>
                {t("catalogue.uom.code").text}
                <span className="register-field-required">*</span>
              </span>
              <input
                type="text"
                required
                maxLength={10}
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
              />
            </label>

            <label className="catalogue-form-field">
              <span>
                {t("catalogue.uom.name_en").text}
                <span className="register-field-required">*</span>
              </span>
              <input
                type="text"
                required
                maxLength={100}
                value={nameEn}
                onChange={(e) => setNameEn(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.uom.name_si").text}</span>
              <input
                type="text"
                maxLength={100}
                value={nameSi}
                onChange={(e) => setNameSi(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.uom.name_ta").text}</span>
              <input
                type="text"
                maxLength={100}
                value={nameTa}
                onChange={(e) => setNameTa(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field catalogue-form-field--full">
              <span>{t("catalogue.uom.description").text}</span>
              <input
                type="text"
                maxLength={255}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </label>

            <div className="catalogue-checkbox-row">
              <input
                type="checkbox"
                id="define-is-weight"
                className="uom-filter-checkbox"
                checked={isWeight}
                onChange={(e) => setIsWeight(e.target.checked)}
              />
              <label htmlFor="define-is-weight" className="catalogue-checkbox-row__label">
                {t("catalogue.uom.is_weight").text}
              </label>
            </div>

            <div className="catalogue-form-actions">
              <button
                type="submit"
                className="modern-btn modern-btn--primary"
                disabled={defineMutation.isPending}
              >
                {defineMutation.isPending ? "..." : t("catalogue.uom.save").text}
              </button>
              <button
                type="button"
                className="modern-btn modern-btn--secondary"
                onClick={() => {
                  setShowDefineForm(false);
                  setErrorMsg("");
                }}
              >
                {t("catalogue.uom.cancel").text}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Edit Form Card */}
      {editingUom && (
        <div className="catalogue-department-card">
          <div className="catalogue-department-card__header">
            <div className="catalogue-department-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.121 2.121 0 1 1 3 3L7 19l-4 1 1-4Z" />
              </svg>
            </div>
            <h3 className="catalogue-department-card__title">
              {t("catalogue.uom.edit_title").text}: {editingUom.uomCode}
            </h3>
          </div>
          <form onSubmit={handleUpdateSubmit} className="catalogue-form-grid">
            <label className="catalogue-form-field">
              <span>{t("catalogue.uom.code").text}</span>
              <input type="text" disabled value={editingUom.uomCode} />
            </label>

            <label className="catalogue-form-field">
              <span>
                {t("catalogue.uom.name_en").text}
                <span className="register-field-required">*</span>
              </span>
              <input
                type="text"
                required
                maxLength={100}
                value={editNameEn}
                onChange={(e) => setEditNameEn(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.uom.name_si").text}</span>
              <input
                type="text"
                maxLength={100}
                value={editNameSi}
                onChange={(e) => setEditNameSi(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.uom.name_ta").text}</span>
              <input
                type="text"
                maxLength={100}
                value={editNameTa}
                onChange={(e) => setEditNameTa(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field catalogue-form-field--full">
              <span>{t("catalogue.uom.description").text}</span>
              <input
                type="text"
                maxLength={255}
                value={editDescription}
                onChange={(e) => setEditDescription(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.uom.status").text}</span>
              <select
                value={editStatus}
                onChange={(e) => setEditStatus(e.target.value as "ACTIVE" | "INACTIVE")}
              >
                <option value="ACTIVE">{t("catalogue.uom.status.ACTIVE").text}</option>
                <option value="INACTIVE">{t("catalogue.uom.status.INACTIVE").text}</option>
              </select>
            </label>

            <div className="catalogue-checkbox-row">
              <input
                type="checkbox"
                id="edit-is-weight"
                className="uom-filter-checkbox"
                checked={editIsWeight}
                onChange={(e) => setEditIsWeight(e.target.checked)}
              />
              <label htmlFor="edit-is-weight" className="catalogue-checkbox-row__label">
                {t("catalogue.uom.is_weight").text}
              </label>
            </div>

            <div className="catalogue-form-actions">
              <button
                type="submit"
                className="modern-btn modern-btn--primary"
                disabled={updateMutation.isPending}
              >
                {updateMutation.isPending ? "..." : t("catalogue.uom.update").text}
              </button>
              <button
                type="button"
                className="modern-btn modern-btn--secondary"
                onClick={cancelEdit}
              >
                {t("catalogue.uom.cancel").text}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Filter Toolbar */}
      <section className="uom-filter-toolbar" aria-label={t("catalogue.filter.toggle").text}>
        <div className="uom-filter-label-group">
          <span className="uom-filter-icon" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
            </svg>
          </span>
          <span className="uom-filter-title">{t("catalogue.filter.toggle").text}</span>
        </div>

        <label className="uom-filter-toggle">
          <input
            type="checkbox"
            className="uom-filter-checkbox"
            checked={includeInactive}
            onChange={(e) => setIncludeInactive(e.target.checked)}
          />
          <span className="uom-filter-label-text">{t("catalogue.uom.filter.include_inactive").text}</span>
        </label>
      </section>

      {/* Directory Table */}
      <section className="catalogue-section">
        {uomsQuery.isLoading ? (
          <p>{t("catalogue.loading").text}</p>
        ) : uoms.length === 0 ? (
          <p>{t("catalogue.uom.empty").text}</p>
        ) : (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table catalogue-table">
                <thead>
                  <tr>
                    <th>{t("catalogue.uom.code").text}</th>
                    <th>{t("catalogue.uom.name_en").text}</th>
                    <th>{t("catalogue.uom.name_si").text}</th>
                    <th>{t("catalogue.uom.name_ta").text}</th>
                    <th style={{ textAlign: "center" }}>{t("catalogue.uom.type").text}</th>
                    <th style={{ textAlign: "center" }}>{t("catalogue.uom.status").text}</th>
                    {canManage && <th style={{ textAlign: "right" }}>{t("catalogue.uom.edit").text}</th>}
                  </tr>
                </thead>
                <tbody>
                  {uoms.map((uom) => (
                    <tr key={uom.uomCode}>
                      <td><strong>{uom.uomCode}</strong></td>
                      <td>{uom.nameEn}</td>
                      <td>{uom.nameSi ?? "—"}</td>
                      <td>{uom.nameTa ?? "—"}</td>
                      <td style={{ textAlign: "center" }}>
                        <StateChip
                          state={uom.weight ? "issued" : "draft"}
                          label={uom.weight ? t("catalogue.uom.type.weight").text : t("catalogue.uom.type.count").text}
                        />
                      </td>
                      <td style={{ textAlign: "center" }}>
                        <StateChip
                          state={uom.status === "ACTIVE" ? "issued" : "void"}
                          label={
                            uom.status === "ACTIVE"
                              ? t("catalogue.uom.status.ACTIVE").text
                              : t("catalogue.uom.status.INACTIVE").text
                          }
                        />
                      </td>
                      {canManage && (
                        <td style={{ textAlign: "right" }}>
                          <button
                            type="button"
                            className="btn btn-sm btn-outline-secondary"
                            onClick={() => startEdit(uom)}
                          >
                            {t("catalogue.uom.edit").text}
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
