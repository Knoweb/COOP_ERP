import { useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHelloApi } from "./helloApi";
import type { Greeting } from "./helloApi";

/**
 * The template screen: a form that posts a command and a list that reads a query.
 * Rules it shows:
 *   - no visible text is written here; every string is a message id in hello.messages.json
 *   - server data goes through TanStack Query; a successful command invalidates the list
 *   - an API error shows the `title` of the problem document, which the backend has already
 *     translated; the screen never invents its own wording for a business rule
 *   - a text with no translation is shown in English with the EN tag, never hidden
 *   - an instant from the API is formatted by the shell helper, in the business time zone
 */
export function HelloPage() {
  const t = useT();
  const { locale } = useIntl();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();

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
    <main style={{ padding: "2rem", maxWidth: "40rem", margin: "0 auto" }}>
      <h1>{t("hello.title").text}</h1>

      <form onSubmit={submit} style={{ display: "grid", gap: "0.75rem", marginBottom: "2rem" }}>
        <TextField label={t("hello.field.text_en").text} value={textEn} onChange={setTextEn} error={fieldErrors.textEn} required />
        <TextField label={t("hello.field.text_si").text} value={textSi} onChange={setTextSi} error={fieldErrors.textSi} lang="si" />
        <TextField label={t("hello.field.text_ta").text} value={textTa} onChange={setTextTa} error={fieldErrors.textTa} lang="ta" />

        <button type="submit" disabled={register.isPending || !textEn.trim()}>
          {register.isPending ? t("hello.submitting").text : t("hello.register").text}
        </button>

        {register.isSuccess && <p role="status">{t("hello.registered").text}</p>}
        {register.isError && <p role="alert">{errorText(register.error, t("hello.error.generic").text)}</p>}
      </form>

      <section>
        <h2>{t("hello.list.title").text}</h2>
        {greetings.isLoading && <p>{t("hello.list.loading").text}</p>}
        {greetings.isError && <p role="alert">{errorText(greetings.error, t("hello.error.generic").text)}</p>}
        {greetings.data?.length === 0 && <p>{t("hello.list.empty").text}</p>}
        <ul style={{ listStyle: "none", padding: 0 }}>
          {greetings.data?.map((greeting) => (
            <li key={greeting.id} style={{ padding: "0.75rem 0", borderBottom: "1px solid #ddd" }}>
              <GreetingText greeting={greeting} locale={locale} />
              {/* The API sends UTC; only here does it become Colombo wall-clock time. */}
              <time dateTime={greeting.createdAt} style={{ display: "block", fontSize: "0.8rem", opacity: 0.7 }}>
                {formatInstant(greeting.createdAt)}
              </time>
            </li>
          ))}
        </ul>
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
  lang?: string;
  error?: string;
}) {
  return (
    <label style={{ display: "grid", gap: "0.25rem" }}>
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
        <span role="alert" style={{ color: "#b00020", fontSize: "0.85rem" }}>
          {props.error}
        </span>
      )}
    </label>
  );
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}
