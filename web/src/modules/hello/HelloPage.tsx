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
import { PageHeader } from "../../shell/components/PageHeader";
import { useHelloApi } from "./helloApi";
import type { Greeting } from "./helloApi";
import "./hello.css";

/**
 * The template screen: a form that posts a command and a list that reads a query.
 * Rules it shows:
 *   - no visible text is written here; every string is a message id in hello.messages.json
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
export function HelloPage() {
  const t = useT();
  const { locale } = useIntl();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const canRegister = useHasPermission("hello.greeting.register");

  const [textEn, setTextEn] = useState("");
  const [textSi, setTextSi] = useState("");
  const [textTa, setTextTa] = useState("");

  // One key per user action. It changes only after a success, so pressing the button again
  // after a network failure repeats the same request and cannot register twice.
  const api = useHelloApi();
  const idempotencyKey = useIdempotencyKey();

  const greetings = useQuery({
    queryKey: ["hello", "greetings", locale],
    queryFn: () => api.listGreetings()
  });

  const register = useMutation({
    mutationFn: () =>
      api.registerGreeting({ textEn, textSi: textSi || null, textTa: textTa || null }, idempotencyKey.current()),
    onSuccess: () => {
      idempotencyKey.next();
      setTextEn("");
      setTextSi("");
      setTextTa("");
      queryClient.invalidateQueries({ queryKey: ["hello", "greetings"] });
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
    <main className="shell-page hello-page">
      <PageHeader
        icon={
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            <path d="M8 9h8" />
            <path d="M8 13h5" />
          </svg>
        }
        title={t("hello.title").text}
      />

      {!canRegister && (
        <div className="hello-readonly-banner">
          <span className="hello-readonly-banner__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          </span>
          <p role="note" className="hello-readonly-banner__text">
            {t("hello.read_only").text}
          </p>
        </div>
      )}

      {canRegister && (
        <section className="hello-form-card">
          <form onSubmit={submit} className="hello-form">
            <div className="hello-form-fields">
              <TextField
                label={t("hello.field.text_en").text}
                value={textEn}
                onChange={setTextEn}
                error={fieldErrors.textEn}
                required
                fullWidth
              />
              <TextField
                label={t("hello.field.text_si").text}
                value={textSi}
                onChange={setTextSi}
                error={fieldErrors.textSi}
                lang="si"
              />
              <TextField
                label={t("hello.field.text_ta").text}
                value={textTa}
                onChange={setTextTa}
                error={fieldErrors.textTa}
                lang="ta"
              />
            </div>

            <div className="hello-form-actions">
              <button
                type="submit"
                className="hello-submit-btn"
                disabled={register.isPending || !textEn.trim()}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M12 5v14" />
                  <path d="M5 12h14" />
                </svg>
                <span>{register.isPending ? t("hello.submitting").text : t("hello.register").text}</span>
              </button>
            </div>

            {register.isSuccess && (
              <div className="hello-feedback hello-feedback--success">
                <span className="hello-feedback__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                </span>
                <p role="status" className="hello-feedback__text">
                  {t("hello.registered").text}
                </p>
              </div>
            )}

            {register.isError && (
              <div className="hello-feedback hello-feedback--error">
                <span className="hello-feedback__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <circle cx="12" cy="12" r="10" />
                    <line x1="12" y1="8" x2="12" y2="12" />
                    <line x1="12" y1="16" x2="12.01" y2="16" />
                  </svg>
                </span>
                <p role="alert" className="hello-feedback__text">
                  {errorText(register.error, t("hello.error.generic").text)}
                </p>
              </div>
            )}
          </form>
        </section>
      )}

      <section className="hello-list-section">
        <div className="hello-list-header">
          <div className="hello-list-heading">
            <span className="hello-list-heading__accent" aria-hidden="true" />
            <h2 className="hello-list-title">{t("hello.list.title").text}</h2>
          </div>
          {greetings.data && (
            <span className="hello-count-pill" aria-hidden="true">
              {greetings.data.length}
            </span>
          )}
        </div>

        {greetings.isLoading && (
          <div className="hello-state-card">
            <p className="hello-state-card__text">{t("hello.list.loading").text}</p>
          </div>
        )}

        {greetings.isError && (
          <div className="hello-feedback hello-feedback--error">
            <span className="hello-feedback__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
            </span>
            <p role="alert" className="hello-feedback__text">
              {errorText(greetings.error, t("hello.error.generic").text)}
            </p>
          </div>
        )}

        {greetings.data?.length === 0 && (
          <div className="hello-state-card">
            <span className="hello-state-card__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
            </span>
            <p className="hello-state-card__text">{t("hello.list.empty").text}</p>
          </div>
        )}

        {greetings.data && greetings.data.length > 0 && (
          <ul className="hello-list">
            {greetings.data.map((greeting) => (
              <li key={greeting.id} className="hello-item">
                <span className="hello-item__avatar" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                  </svg>
                </span>
                <div className="hello-item__body">
                  <div className="hello-item__text">
                    <GreetingText greeting={greeting} locale={locale} />
                  </div>
                  {/* The API sends UTC; only here does it become Colombo wall-clock time. */}
                  <time dateTime={greeting.createdAt} className="hello-item__meta">
                    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="12 6 12 12 16 14" />
                    </svg>
                    <span>{formatInstant(greeting.createdAt)}</span>
                  </time>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

/** The greeting in the user's language, or in English with the EN tag when not translated. */
function GreetingText({ greeting, locale }: { greeting: Greeting; locale: string }) {
  const translated = locale === "si" ? greeting.textSi : locale === "ta" ? greeting.textTa : greeting.textEn;
  if (translated) {
    return <span lang={locale}>{translated}</span>;
  }
  return (
    <span lang="en">
      {greeting.textEn}
      <LangFallbackTag />
    </span>
  );
}

function TextField(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  fullWidth?: boolean;
  lang?: string;
  error?: string;
}) {
  return (
    <label className={`hello-field ${props.fullWidth ? "hello-field--primary" : ""}`.trim()}>
      <span className="hello-field__label">
        <span className="hello-field__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="m5 8 6 6" />
            <path d="m4 14 6-6 2-3" />
            <path d="M2 5h12" />
            <path d="M7 2h1" />
            <path d="m22 22-5-10-5 10" />
            <path d="M14 18h6" />
          </svg>
        </span>
        <span>{props.label}</span>
      </span>
      <input
        type="text"
        className="hello-field__input"
        value={props.value}
        required={props.required}
        lang={props.lang}
        aria-invalid={props.error ? true : undefined}
        onChange={(event) => props.onChange(event.target.value)}
      />
      {props.error && (
        <span role="alert" className="hello-field__error">
          {props.error}
        </span>
      )}
    </label>
  );
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}
