import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useCatalogueApi } from "./catalogueApi";
import { SkuFields } from "./SkuFields";
import "./catalogue.css";
import { EMPTY_FORM, errorText, requestOf, type SkuForm } from "./skuView";

/** A new item, created as a DRAFT of the caller's entity (22A section 6, CreateSku); its card follows. */
export function NewSkuPage() {
  const t = useT();
  const api = useCatalogueApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();
  const [form, setForm] = useState<SkuForm>(EMPTY_FORM);

  const create = useMutation({
    mutationFn: () => api.createSku(requestOf(form), idempotencyKey.current()),
    onSuccess: (sku) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["catalogue", "skus"] });
      navigate(`/catalogue/skus/${sku.skuId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <main className="shell-page sku-editor-container">
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

      <div className="sku-header-card">
        <h1>{t("catalogue.new.title").text}</h1>
      </div>

      <div className="sku-editor-card">
        <form onSubmit={submit}>
          <SkuFields form={form} onChange={(change) => setForm((current) => ({ ...current, ...change }))} />
          <div className="sku-form-actions">
            <button
              type="submit"
              className="register-submit-btn"
              disabled={create.isPending || !form.nameEn.trim() || !form.taxCategoryId}
            >
              {t("catalogue.new.submit").text}
            </button>
          </div>
          {create.isError && (
            <p role="alert" className="register-error-banner" style={{ marginTop: "var(--space-2)" }}>
              {errorText(create.error, t("catalogue.error.generic").text)}
            </p>
          )}
        </form>
      </div>
    </main>
  );
}
