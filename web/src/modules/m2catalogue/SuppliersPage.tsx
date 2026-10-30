import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { useCatalogueApi } from "./catalogueApi";
import { errorText } from "./skuView";


/**
 * Suppliers management page (22A section 8, Suppliers).
 * Displays suppliers of the current entity and allows registering new suppliers.
 */
export function SuppliersPage() {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canManage = useHasPermission("cat.supplier.manage");
  const registerKey = useIdempotencyKey();

  const [name, setName] = useState("");
  const [successMsg, setSuccessMsg] = useState("");

  const suppliersQuery = useQuery({
    queryKey: ["catalogue", "suppliers"],
    queryFn: () => api.listSuppliers()
  });

  const registerMutation = useMutation({
    mutationFn: () =>
      api.registerSupplier(
        { name: name.trim() },
        registerKey.current()
      ),
    onSuccess: () => {
      registerKey.next();
      setName("");
      setSuccessMsg(t("catalogue.suppliers.success").text);
      queryClient.invalidateQueries({ queryKey: ["catalogue", "suppliers"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        registerKey.next();
      }
    }
  });

  const handleRegisterSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSuccessMsg("");
    if (!name.trim()) return;
    registerMutation.mutate();
  };

  const suppliers = suppliersQuery.data ?? [];

  return (
    <main className="shell-page">
      <div style={{ marginBottom: "var(--space-2)" }}>
        <Link className="back-pill-btn" to="/catalogue">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("catalogue.back").text}
        </Link>
      </div>

      <PageHeader
        icon="catalogue"
        title={t("catalogue.suppliers.title").text}
      />
      <p className="catalogue-import-subtitle">{t("catalogue.suppliers.description").text}</p>

      {/* Register Supplier Form */}
      {canManage && (
        <section className="catalogue-section supplier-register-card">
          <h2>{t("catalogue.suppliers.register").text}</h2>
          <form onSubmit={handleRegisterSubmit} className="supplier-register-form">
            <div className="supplier-register-field-wrap">
              <label className="catalogue-form-field">
                <span className="catalogue-field-label">{t("catalogue.suppliers.name").text}</span>
                <input
                  type="text"
                  required
                  maxLength={120}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t("catalogue.suppliers.placeholder").text}
                  className="supplier-name-input"
                />
              </label>
            </div>
            <button
              type="submit"
              className="register-submit-btn supplier-register-btn"
              disabled={registerMutation.isPending || !name.trim()}
            >
              {t("catalogue.suppliers.submit").text}
            </button>
          </form>

          {successMsg && (
            <div className="import-alert import-alert--success" role="status" style={{ marginTop: "var(--space-2)" }}>
              <span>{successMsg}</span>
            </div>
          )}

          {registerMutation.isError && (
            <p role="alert" className="error-text" style={{ marginTop: "var(--space-2)" }}>
              {errorText(registerMutation.error, t("catalogue.error.generic").text)}
            </p>
          )}
        </section>
      )}

      {/* Suppliers Table */}
      <section className="catalogue-section">
        {suppliersQuery.isLoading && <p>{t("catalogue.loading").text}</p>}

        {suppliers.length === 0 && !suppliersQuery.isLoading && (
          <div className="supplier-empty-state">
            <p>{t("catalogue.suppliers.empty").text}</p>
          </div>
        )}

        {suppliers.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table catalogue-table">
                <thead>
                  <tr>
                    <th>{t("catalogue.suppliers.name").text}</th>
                    <th>{t("catalogue.column.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {suppliers.map((s) => {
                    const chipState: ChipState = s.status === "ACTIVE" ? "issued" : "void";
                    return (
                      <tr key={s.supplierId}>
                        <td>{s.name}</td>
                        <td>
                          <StateChip
                            state={chipState}
                            label={t(`catalogue.supplier.status.${s.status}`).text}
                          />
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
