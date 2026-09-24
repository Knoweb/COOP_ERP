import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { usePartyApi } from "./partyApi";
import type { RegisterSocietyRequest } from "./partyApi";
import { errorText } from "./SocietyRegisterPage";

const ENTITY_TYPES: RegisterSocietyRequest["entityType"][] = ["MPCS", "DISTRIBUTOR"];
const LANGUAGES: RegisterSocietyRequest["defaultLanguage"][] = ["en", "si", "ta"];
const MONTHS = Array.from({ length: 12 }, (_, index) => index + 1);

/**
 * Registering one society (21A section 6, RegisterEntity): the form posts the command, the
 * server validates it (a duplicate code, a missing English name) and answers with a problem
 * document whose field errors go under the fields and whose title goes under the form, both
 * already in the user's language. On success the browser goes to the new society's card, in
 * ONBOARDING: activating it is a separate command with its own prerequisites.
 */
export function RegisterSocietyPage() {
  const t = useT();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const api = usePartyApi();
  const idempotencyKey = useIdempotencyKey();
  const canRegister = useHasPermission("gov.entity.register");

  const [form, setForm] = useState<RegisterSocietyRequest>({
    entityCode: "",
    entityType: "MPCS",
    legalNameEn: "",
    legalNameSi: "",
    legalNameTa: "",
    registrationNo: "",
    vatRegistrationNo: "",
    district: "",
    defaultLanguage: "en",
    financialYearStartMonth: 1
  });

  const set = <K extends keyof RegisterSocietyRequest>(key: K, value: RegisterSocietyRequest[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const register = useMutation({
    mutationFn: () => api.registerSociety(cleaned(form), idempotencyKey.current()),
    onSuccess: (society) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["party", "societies"] });
      navigate(`/party/societies/${society.entityId}`);
    },
    onError: (error) => {
      // A problem document means the server answered: the next attempt is a new action.
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });

  const fieldErrors = register.error instanceof ApiProblem ? register.error.fieldErrors : {};

  const submit = (event: FormEvent) => {
    event.preventDefault();
    register.mutate();
  };

  if (!canRegister) {
    return (
      <main className="shell-page">
        <h1>{t("party.new.title").text}</h1>
        <p role="note">{t("party.new.not_allowed").text}</p>
      </main>
    );
  }

  return (
    <main className="shell-page">
      <p>
        <Link to="/party/societies">{t("party.back_to_register").text}</Link>
      </p>
      <h1>{t("party.new.title").text}</h1>

      <form onSubmit={submit} style={{ display: "grid", gap: "var(--target-gap)" }}>
        <TextField label={t("party.field.code").text} value={form.entityCode} onChange={(v) => set("entityCode", v)} error={fieldErrors.entityCode} required maxLength={12} />

        <label style={field}>
          {t("party.field.type").text}
          <select value={form.entityType} onChange={(event) => set("entityType", event.target.value as RegisterSocietyRequest["entityType"])}>
            {ENTITY_TYPES.map((type) => (
              <option key={type} value={type}>
                {t(`party.type.${type}`).text}
              </option>
            ))}
          </select>
        </label>

        <TextField label={t("party.field.name_en").text} value={form.legalNameEn} onChange={(v) => set("legalNameEn", v)} error={fieldErrors.legalNameEn} required />
        <TextField label={t("party.field.name_si").text} value={form.legalNameSi ?? ""} onChange={(v) => set("legalNameSi", v)} error={fieldErrors.legalNameSi} lang="si" />
        <TextField label={t("party.field.name_ta").text} value={form.legalNameTa ?? ""} onChange={(v) => set("legalNameTa", v)} error={fieldErrors.legalNameTa} lang="ta" />
        <TextField label={t("party.field.registration_no").text} value={form.registrationNo ?? ""} onChange={(v) => set("registrationNo", v)} error={fieldErrors.registrationNo} maxLength={40} />
        <TextField label={t("party.field.vat_no").text} value={form.vatRegistrationNo ?? ""} onChange={(v) => set("vatRegistrationNo", v)} error={fieldErrors.vatRegistrationNo} maxLength={20} />
        <TextField label={t("party.field.district").text} value={form.district ?? ""} onChange={(v) => set("district", v)} error={fieldErrors.district} maxLength={40} />

        <label style={field}>
          {t("party.field.language").text}
          <select value={form.defaultLanguage} onChange={(event) => set("defaultLanguage", event.target.value as RegisterSocietyRequest["defaultLanguage"])}>
            {LANGUAGES.map((language) => (
              <option key={language} value={language}>
                {t(`party.language.${language}`).text}
              </option>
            ))}
          </select>
        </label>

        <label style={field}>
          {t("party.field.fy_start").text}
          <select value={form.financialYearStartMonth} onChange={(event) => set("financialYearStartMonth", Number(event.target.value))}>
            {MONTHS.map((month) => (
              <option key={month} value={month}>
                {t(`party.month.${month}`).text}
              </option>
            ))}
          </select>
          {fieldErrors.financialYearStartMonth && <FieldError text={fieldErrors.financialYearStartMonth} />}
        </label>

        <button type="submit" disabled={register.isPending || !form.entityCode.trim() || !form.legalNameEn.trim()}>
          {register.isPending ? t("party.new.submitting").text : t("party.new.submit").text}
        </button>

        {register.isError && <p role="alert">{errorText(register.error, t("party.error.generic").text)}</p>}
      </form>
    </main>
  );
}

/** Blank optional fields go up as null, not "": the slice says nullable, and "" is not a district. */
function cleaned(form: RegisterSocietyRequest): RegisterSocietyRequest {
  const orNull = (value: string | null | undefined) => (value && value.trim() ? value.trim() : null);
  return {
    ...form,
    entityCode: form.entityCode.trim(),
    legalNameEn: form.legalNameEn.trim(),
    legalNameSi: orNull(form.legalNameSi),
    legalNameTa: orNull(form.legalNameTa),
    registrationNo: orNull(form.registrationNo),
    vatRegistrationNo: orNull(form.vatRegistrationNo),
    district: orNull(form.district)
  };
}

const field = { display: "grid", gap: "var(--space-half)" };

export function TextField(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  maxLength?: number;
  lang?: string;
  error?: string;
}) {
  return (
    <label style={field}>
      {props.label}
      <input
        type="text"
        value={props.value}
        required={props.required}
        maxLength={props.maxLength}
        lang={props.lang}
        aria-invalid={props.error ? true : undefined}
        onChange={(event) => props.onChange(event.target.value)}
      />
      {props.error && <FieldError text={props.error} />}
    </label>
  );
}

function FieldError({ text }: { text: string }) {
  return (
    <span role="alert" style={{ color: "var(--color-alert-text)", fontSize: "var(--font-size-sm)" }}>
      {text}
    </span>
  );
}
