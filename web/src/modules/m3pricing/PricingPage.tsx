import { useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { usePricingApi } from "./pricingApi";
import type { PriceList } from "./pricingApi";

/**
 * The template screen: a form that posts a command and a list that reads a query.
 * Rules it shows:
 *   - no visible text is written here; every string is a message id in pricing.messages.json
 *   - server data goes through TanStack Query; a successful command invalidates the list
 *   - an API error shows the `title` of the problem document, which the backend has already
 *     translated; the screen never invents its own wording for a business rule
 *   - a text with no translation is shown in English with the EN tag, never hidden
 *   - an instant from the API is formatted by the shell helper, in the business time zone
 *   - an action the user has no permission for is not offered, and the screen says why (doc 30);
 *     the server checks the permission anyway, and its refusal is shown like any other problem
 *   - no colour and no size is written here: the page takes its shape from the shell ("shell-page")
 *     and every distance, colour and font size is a token of design/tokens.css, var(--space-2).
 *     A literal such as "#b00020" or "12px" in a module fails the build (design/moduleStyle.test.ts)
 *   - an amount of money is shown with <MoneyDisplay amount={...} /> (shell/components), never
 *     formatted or calculated in the screen (this template has no amount to show)
 */
export function PricingPage() {
  const t = useT();
  const { locale } = useIntl();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const canRegister = useHasPermission("prc.price_list.register");

  const [textEn, setTextEn] = useState("");
  const [textSi, setTextSi] = useState("");
  const [textTa, setTextTa] = useState("");

  // One key per user action. It changes only after a success, so pressing the button again
  // after a network failure repeats the same request and cannot register twice.
  const api = usePricingApi();
  const idempotencyKey = useIdempotencyKey();

  const priceLists = useQuery({
    queryKey: ["pricing", "priceLists", locale],
    queryFn: () => api.listPriceLists()
  });

  const register = useMutation({
    mutationFn: () =>
      api.registerPriceList({ textEn, textSi: textSi || null, textTa: textTa || null }, idempotencyKey.current()),
    onSuccess: () => {
      idempotencyKey.next();
      setTextEn("");
      setTextSi("");
      setTextTa("");
      queryClient.invalidateQueries({ queryKey: ["pricing", "priceLists"] });
    },
    onError: (error) => {
      // A problem document means the server answered: this request is finished, so the next
      // attempt (with corrected input) is a new action and needs a new key.
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });

  // What the server refused field by field (400 request.invalid), shown under each field; any
  // other problem is one sentence under the form. Both arrive in the user's language.
  const fieldErrors = register.error instanceof ApiProblem ? register.error.fieldErrors : {};

  const submit = (event: FormEvent) => {
    event.preventDefault();
    register.mutate();
  };

  return (
    <main className="shell-page">
      <h1>{t("pricing.title").text}</h1>

      {!canRegister && <p role="note">{t("pricing.read_only").text}</p>}

      {canRegister && (
        <form onSubmit={submit} style={{ display: "grid", gap: "var(--target-gap)", marginBottom: "var(--space-4)" }}>
          <TextField label={t("pricing.field.text_en").text} value={textEn} onChange={setTextEn} error={fieldErrors.textEn} required />
          <TextField label={t("pricing.field.text_si").text} value={textSi} onChange={setTextSi} error={fieldErrors.textSi} lang="si" />
          <TextField label={t("pricing.field.text_ta").text} value={textTa} onChange={setTextTa} error={fieldErrors.textTa} lang="ta" />

          <button type="submit" disabled={register.isPending || !textEn.trim()}>
            {register.isPending ? t("pricing.submitting").text : t("pricing.register").text}
          </button>

          {register.isSuccess && <p role="status">{t("pricing.registered").text}</p>}
          {register.isError && <p role="alert">{errorText(register.error, t("pricing.error.generic").text)}</p>}
        </form>
      )}

      <section>
        <h2>{t("pricing.list.title").text}</h2>
        {priceLists.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {priceLists.isError && <p role="alert">{errorText(priceLists.error, t("pricing.error.generic").text)}</p>}
        {priceLists.data?.length === 0 && <p>{t("pricing.list.empty").text}</p>}
        <ul style={{ listStyle: "none", padding: 0 }}>
          {priceLists.data?.map((priceList) => (
            <li key={priceList.id} style={{ padding: "var(--space-1) 0", borderBottom: "var(--border-width) solid var(--color-border)" }}>
              <PriceListText priceList={priceList} locale={locale} />
              {/* The API sends UTC; only here does it become Colombo wall-clock time. */}
              <time dateTime={priceList.createdAt} style={{ display: "block", fontSize: "var(--font-size-sm)", color: "var(--color-text-muted)" }}>
                {formatInstant(priceList.createdAt)}
              </time>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}

/** The price list in the user's language, or in English with the EN tag when not translated. */
function PriceListText({ priceList, locale }: { priceList: PriceList; locale: string }) {
  const translated = locale === "si" ? priceList.textSi : locale === "ta" ? priceList.textTa : priceList.textEn;
  if (translated) {
    return <span lang={locale}>{translated}</span>;
  }
  return (
    <span lang="en">
      {priceList.textEn}
      <LangFallbackTag />
    </span>
  );
}

function TextField(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  lang?: string;
  error?: string;
}) {
  return (
    <label style={{ display: "grid", gap: "var(--space-half)" }}>
      {props.label}
      <input
        type="text"
        value={props.value}
        required={props.required}
        lang={props.lang}
        aria-invalid={props.error ? true : undefined}
        onChange={(event) => props.onChange(event.target.value)}
      />
      {props.error && (
        <span role="alert" style={{ color: "var(--color-alert-text)", fontSize: "var(--font-size-sm)" }}>
          {props.error}
        </span>
      )}
    </label>
  );
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}
