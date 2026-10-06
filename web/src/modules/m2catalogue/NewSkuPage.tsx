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
    <main className="shell-page">
      <Link className="back-link" to="/catalogue">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("catalogue.back").text}
      </Link>
      <h1>{t("catalogue.new.title").text}</h1>
      <form onSubmit={submit} className="catalogue-form-row">
        <SkuFields form={form} onChange={(change) => setForm((current) => ({ ...current, ...change }))} />
        <div>
          <button type="submit" disabled={create.isPending || !form.nameEn.trim() || !form.taxCategoryId}>
            {t("catalogue.new.submit").text}
          </button>
        </div>
        {create.isError && <p role="alert">{errorText(create.error, t("catalogue.error.generic").text)}</p>}
      </form>
    </main>
  );
}
