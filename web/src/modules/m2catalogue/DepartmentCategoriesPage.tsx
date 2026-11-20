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
  type DepartmentCategory
} from "./catalogueApi";
import { errorText } from "./skuView";

export function DepartmentCategoriesPage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canManage = useHasPermission("cat.department.manage");

  const defineKey = useIdempotencyKey();
  const updateKey = useIdempotencyKey();

  const [showDefineForm, setShowDefineForm] = useState(false);
  const [editingDepartment, setEditingDepartment] = useState<DepartmentCategory | null>(null);

  // Define Form State
  const [code, setCode] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [nameSi, setNameSi] = useState("");
  const [nameTa, setNameTa] = useState("");
  const [description, setDescription] = useState("");
  const [displayOrder, setDisplayOrder] = useState(10);

  // Edit Form State
  const [editNameEn, setEditNameEn] = useState("");
  const [editNameSi, setEditNameSi] = useState("");
  const [editNameTa, setEditNameTa] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editDisplayOrder, setEditDisplayOrder] = useState(10);
  const [editStatus, setEditStatus] = useState<"ACTIVE" | "INACTIVE">("ACTIVE");

  const [successMsg, setSuccessMsg] = useState("");
  const [errorMsg, setErrorMsg] = useState("");

  const departmentsQuery = useQuery({
    queryKey: ["catalogue", "departments"],
    queryFn: () => api.listDepartmentCategories()
  });

  const defineMutation = useMutation({
    mutationFn: () =>
      api.defineDepartmentCategory(
        {
          code: code.trim().toUpperCase(),
          nameEn: nameEn.trim(),
          nameSi: nameSi.trim() || undefined,
          nameTa: nameTa.trim() || undefined,
          description: description.trim() || undefined,
          displayOrder: displayOrder != null ? Number(displayOrder) : undefined
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
      setDisplayOrder(10);
      setShowDefineForm(false);
      setSuccessMsg(t("catalogue.departments.success.defined").text);
      setErrorMsg("");
      queryClient.invalidateQueries({ queryKey: ["catalogue", "departments"] });
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
      if (!editingDepartment) throw new Error("No department selected for edit");
      return api.updateDepartmentCategory(
        editingDepartment.departmentId,
        {
          nameEn: editNameEn.trim(),
          nameSi: editNameSi.trim() || undefined,
          nameTa: editNameTa.trim() || undefined,
          description: editDescription.trim() || undefined,
          displayOrder: editDisplayOrder != null ? Number(editDisplayOrder) : undefined,
          status: editStatus
        },
        updateKey.current()
      );
    },
    onSuccess: () => {
      updateKey.next();
      setEditingDepartment(null);
      setSuccessMsg(t("catalogue.departments.success.updated").text);
      setErrorMsg("");
      queryClient.invalidateQueries({ queryKey: ["catalogue", "departments"] });
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

  const startEdit = (dept: DepartmentCategory) => {
    setEditingDepartment(dept);
    setEditNameEn(dept.nameEn);
    setEditNameSi(dept.nameSi ?? "");
    setEditNameTa(dept.nameTa ?? "");
    setEditDescription(dept.description ?? "");
    setEditDisplayOrder(dept.displayOrder ?? 10);
    setEditStatus((dept.status as "ACTIVE" | "INACTIVE") ?? "ACTIVE");
    setShowDefineForm(false);
    setSuccessMsg("");
    setErrorMsg("");
  };

  const cancelEdit = () => {
    setEditingDepartment(null);
    setErrorMsg("");
  };

  const departments = departmentsQuery.data ?? [];

  return (
    <div className="catalogue-departments-page app-page-shell">
      <PageHeader
        title={t("catalogue.departments.title").text}
        icon="departments"
        actions={
          canManage && !showDefineForm && !editingDepartment ? (
            <button
              type="button"
              className="modern-btn modern-btn--primary"
              onClick={() => {
                setShowDefineForm(true);
                setSuccessMsg("");
                setErrorMsg("");
              }}
            >
              + {t("catalogue.departments.new_button").text}
            </button>
          ) : undefined
        }
      />

      <p className="catalogue-import-subtitle" style={{ marginBottom: "var(--space-2)" }}>{t("catalogue.departments.subtitle").text}</p>

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
                <rect x="9" y="3" width="6" height="5" rx="1" />
                <rect x="3" y="16" width="6" height="5" rx="1" />
                <rect x="15" y="16" width="6" height="5" rx="1" />
                <path d="M12 8v4" />
                <path d="M6 16v-2a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v2" />
              </svg>
            </div>
            <h3 className="catalogue-department-card__title">{t("catalogue.departments.define_title").text}</h3>
          </div>
          <form onSubmit={handleDefineSubmit} className="catalogue-form-grid">
            <label className="catalogue-form-field">
              <span>
                {t("catalogue.departments.code").text}
                <span className="register-field-required">*</span>
              </span>
              <input
                type="text"
                required
                maxLength={32}
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}

              />
            </label>

            <label className="catalogue-form-field">
              <span>
                {t("catalogue.departments.name_en").text}
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
              <span>{t("catalogue.departments.name_si").text}</span>
              <input
                type="text"
                maxLength={100}
                value={nameSi}
                onChange={(e) => setNameSi(e.target.value)}

              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.departments.name_ta").text}</span>
              <input
                type="text"
                maxLength={100}
                value={nameTa}
                onChange={(e) => setNameTa(e.target.value)}

              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.departments.display_order").text}</span>
              <input
                type="number"
                min="0"
                max="9999"
                value={displayOrder}
                onChange={(e) => setDisplayOrder(Number(e.target.value))}
              />
            </label>

            <label className="catalogue-form-field catalogue-form-field--full">
              <span>{t("catalogue.departments.description").text}</span>
              <textarea
                rows={2}
                maxLength={500}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </label>

            <div className="catalogue-form-actions">
              <button
                type="submit"
                className="modern-btn modern-btn--primary"
                disabled={defineMutation.isPending || !code.trim() || !nameEn.trim()}
              >
                {t("catalogue.departments.save").text}
              </button>
              <button
                type="button"
                className="modern-btn modern-btn--secondary"
                onClick={() => setShowDefineForm(false)}
              >
                {t("catalogue.departments.cancel").text}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Edit Form Card */}
      {editingDepartment && (
        <div className="catalogue-department-card">
          <div className="catalogue-department-card__header">
            <div className="catalogue-department-card__icon-badge" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.121 2.121 0 1 1 3 3L7 19l-4 1 1-4Z" />
              </svg>
            </div>
            <h3 className="catalogue-department-card__title">
              {t("catalogue.departments.edit_title").text}: {editingDepartment.code}
            </h3>
          </div>
          <form onSubmit={handleUpdateSubmit} className="catalogue-form-grid">
            <label className="catalogue-form-field">
              <span>
                {t("catalogue.departments.name_en").text}
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
              <span>{t("catalogue.departments.name_si").text}</span>
              <input
                type="text"
                maxLength={100}
                value={editNameSi}
                onChange={(e) => setEditNameSi(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.departments.name_ta").text}</span>
              <input
                type="text"
                maxLength={100}
                value={editNameTa}
                onChange={(e) => setEditNameTa(e.target.value)}
              />
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.departments.status").text}</span>
              <select
                value={editStatus}
                onChange={(e) => setEditStatus(e.target.value as "ACTIVE" | "INACTIVE")}
              >
                <option value="ACTIVE">{t("catalogue.departments.status.ACTIVE").text}</option>
                <option value="INACTIVE">{t("catalogue.departments.status.INACTIVE").text}</option>
              </select>
            </label>

            <label className="catalogue-form-field">
              <span>{t("catalogue.departments.display_order").text}</span>
              <input
                type="number"
                min="0"
                max="9999"
                value={editDisplayOrder}
                onChange={(e) => setEditDisplayOrder(Number(e.target.value))}
              />
            </label>

            <label className="catalogue-form-field catalogue-form-field--full">
              <span>{t("catalogue.departments.description").text}</span>
              <textarea
                rows={2}
                maxLength={500}
                value={editDescription}
                onChange={(e) => setEditDescription(e.target.value)}
              />
            </label>

            <div className="catalogue-form-actions">
              <button
                type="submit"
                className="modern-btn modern-btn--primary"
                disabled={updateMutation.isPending || !editNameEn.trim()}
              >
                {t("catalogue.departments.update").text}
              </button>
              <button
                type="button"
                className="modern-btn modern-btn--secondary"
                onClick={cancelEdit}
              >
                {t("catalogue.departments.cancel").text}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Directory Table */}
      <section className="catalogue-section">
        {departmentsQuery.isLoading ? (
          <p>{t("catalogue.loading").text}</p>
        ) : departments.length === 0 ? (
          <p>{t("catalogue.departments.empty").text}</p>
        ) : (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table catalogue-table">
                <thead>
                  <tr>
                    <th>{t("catalogue.departments.code").text}</th>
                    <th>{t("catalogue.departments.name_en").text}</th>
                    <th>{t("catalogue.departments.name_si").text}</th>
                    <th>{t("catalogue.departments.name_ta").text}</th>
                    <th style={{ textAlign: "center" }}>{t("catalogue.departments.display_order").text}</th>
                    <th style={{ textAlign: "center" }}>{t("catalogue.departments.status").text}</th>
                    {canManage && <th style={{ textAlign: "right" }}>{t("catalogue.departments.edit").text}</th>}
                  </tr>
                </thead>
                <tbody>
                  {departments.map((dept) => (
                    <tr key={dept.departmentId}>
                      <td><strong>{dept.code}</strong></td>
                      <td>{dept.nameEn}</td>
                      <td>{dept.nameSi ?? "—"}</td>
                      <td>{dept.nameTa ?? "—"}</td>
                      <td style={{ textAlign: "center" }}>{dept.displayOrder}</td>
                      <td style={{ textAlign: "center" }}>
                        <StateChip
                          state={dept.status === "ACTIVE" ? "issued" : "void"}
                          label={
                            dept.status === "ACTIVE"
                              ? t("catalogue.departments.status.ACTIVE").text
                              : t("catalogue.departments.status.INACTIVE").text
                          }
                        />
                      </td>
                      {canManage && (
                        <td style={{ textAlign: "right" }}>
                          <button
                            type="button"
                            className="btn btn-sm btn-outline-secondary"
                            onClick={() => startEdit(dept)}
                          >
                            {t("catalogue.departments.edit").text}
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
