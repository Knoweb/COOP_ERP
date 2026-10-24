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
import "./catalogue.css";

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
      <Link className="back-link" to="/catalogue">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("catalogue.back").text}
      </Link>

      <PageHeader
        icon="catalogue"
        title={t("catalogue.suppliers.title").text}
      />
      <p className="catalogue-import-subtitle">{t("catalogue.suppliers.description").text}</p>

      {/* Register Supplier Form */}
      {canManage && (
        <section className="catalogue-section">
          <h2>{t("catalogue.suppliers.register").text}</h2>
          <form onSubmit={handleRegisterSubmit} className="catalogue-filter-bar">
            <label className="catalogue-form-field">
              {t("catalogue.suppliers.name").text}
              <input
                type="text"
                required
                maxLength={120}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t("catalogue.suppliers.placeholder").text}
              />
            </label>
            <button
              type="submit"
              className="action-button action-button--primary"
              disabled={registerMutation.isPending || !name.trim()}
            >
              {t("catalogue.suppliers.submit").text}
            </button>
          </form>

          {successMsg && (
            <div className="import-alert import-alert--success" role="status">
              <span>{successMsg}</span>
            </div>
          )}

          {registerMutation.isError && (
            <p role="alert" className="error-text">
              {errorText(registerMutation.error, t("catalogue.error.generic").text)}
            </p>
          )}
        </section>
      )}

      {/* Suppliers Table */}
      <section className="catalogue-section">
        {suppliersQuery.isLoading && <p>{t("catalogue.loading").text}</p>}

        {suppliers.length === 0 && !suppliersQuery.isLoading && (
          <p>{t("catalogue.suppliers.empty").text}</p>
        )}

        {suppliers.length > 0 && (
          <table>
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
        )}
      </section>
    </main>
  );
}
