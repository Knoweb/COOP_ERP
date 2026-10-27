import { useEffect, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import "./m1party.css";
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
      <div className="register-society-container">
        <div>
          <Link className="back-pill-btn" to="/party/societies">
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
            {t("party.back_to_register").text}
          </Link>
        </div>

        <div className="page-heading">
          <div className="page-heading__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="24" height="24" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
              <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
              <circle cx="9" cy="7" r="4" />
              <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
              <path d="M16 3.13a4 4 0 0 1 0 7.75" />
            </svg>
          </div>
          <div className="page-heading__copy">
            <h1>{t("party.new.title").text}</h1>
          </div>
        </div>

        <div className="register-society-card">
          <form onSubmit={submit} noValidate>
            <div className="register-society-grid">
              <TextField
                id="entityCode"
                label={t("party.field.code").text}
                icon={<CodeIcon />}
                value={form.entityCode}
                onChange={(v) => set("entityCode", v)}
                error={fieldErrors.entityCode}
                required
                maxLength={12}
              />

              <FormDropdown
                id="entityType"
                label={t("party.field.type").text}
                icon={<BuildingIcon />}
                value={form.entityType}
                options={ENTITY_TYPES.map((type) => ({
                  value: type,
                  label: t(`party.type.${type}`).text
                }))}
                onChange={(v) => set("entityType", v as RegisterSocietyRequest["entityType"])}
              />

              <TextField
                id="legalNameEn"
                label={t("party.field.name_en").text}
                icon={<FileTextIcon />}
                value={form.legalNameEn}
                onChange={(v) => set("legalNameEn", v)}
                error={fieldErrors.legalNameEn}
                required
              />

              <TextField
                id="legalNameSi"
                label={t("party.field.name_si").text}
                icon={<LanguagesIcon />}
                value={form.legalNameSi ?? ""}
                onChange={(v) => set("legalNameSi", v)}
                error={fieldErrors.legalNameSi}
                lang="si"
              />

              <TextField
                id="legalNameTa"
                label={t("party.field.name_ta").text}
                icon={<LanguagesIcon />}
                value={form.legalNameTa ?? ""}
                onChange={(v) => set("legalNameTa", v)}
                error={fieldErrors.legalNameTa}
                lang="ta"
              />

              <TextField
                id="district"
                label={t("party.field.district").text}
                icon={<MapPinIcon />}
                value={form.district ?? ""}
                onChange={(v) => set("district", v)}
                error={fieldErrors.district}
                maxLength={40}
              />

              <TextField
                id="registrationNo"
                label={t("party.field.registration_no").text}
                icon={<HashIcon />}
                value={form.registrationNo ?? ""}
                onChange={(v) => set("registrationNo", v)}
                error={fieldErrors.registrationNo}
                maxLength={40}
              />

              <TextField
                id="vatRegistrationNo"
                label={t("party.field.vat_no").text}
                icon={<PercentIcon />}
                value={form.vatRegistrationNo ?? ""}
                onChange={(v) => set("vatRegistrationNo", v)}
                error={fieldErrors.vatRegistrationNo}
                maxLength={20}
              />

              <FormDropdown
                id="defaultLanguage"
                label={t("party.field.language").text}
                icon={<GlobeIcon />}
                value={form.defaultLanguage}
                options={LANGUAGES.map((language) => ({
                  value: language,
                  label: t(`party.language.${language}`).text
                }))}
                onChange={(v) => set("defaultLanguage", v as RegisterSocietyRequest["defaultLanguage"])}
              />

              <FormDropdown
                id="financialYearStartMonth"
                label={t("party.field.fy_start").text}
                icon={<CalendarIcon />}
                value={form.financialYearStartMonth}
                options={MONTHS.map((month) => ({
                  value: month,
                  label: t(`party.month.${month}`).text
                }))}
                onChange={(v) => set("financialYearStartMonth", Number(v))}
                error={fieldErrors.financialYearStartMonth}
              />
            </div>

            <div className="register-form-actions">
              <button
                type="submit"
                className="register-submit-btn"
                disabled={register.isPending || !form.entityCode.trim() || !form.legalNameEn.trim()}
              >
                {register.isPending && (
                  <svg
                    className="spin-icon"
                    viewBox="0 0 24 24"
                    width="16"
                    height="16"
                    stroke="currentColor"
                    strokeWidth="2"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                  </svg>
                )}
                {register.isPending ? t("party.new.submitting").text : t("party.new.submit").text}
              </button>
            </div>

            {register.isError && (
              <div role="alert" className="register-error-banner">
                <svg
                  viewBox="0 0 24 24"
                  width="18"
                  height="18"
                  stroke="currentColor"
                  strokeWidth="2"
                  fill="none"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span>{errorText(register.error, t("party.error.generic").text)}</span>
              </div>
            )}
          </form>
        </div>
      </div>
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

export function TextField(props: {
  id: string;
  label: string;
  icon?: ReactNode;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  maxLength?: number;
  lang?: string;
  error?: string;
}) {
  return (
    <div className="register-field-group">
      <label htmlFor={props.id} className="register-field-label">
        {props.icon && <span className="register-field-icon" aria-hidden="true">{props.icon}</span>}
        <span>{props.label}</span>
        {props.required && <span className="register-field-required" aria-hidden="true">*</span>}
      </label>
      <input
        id={props.id}
        type="text"
        className="register-input-control"
        value={props.value}
        required={props.required}
        maxLength={props.maxLength}
        lang={props.lang}
        aria-invalid={props.error ? true : undefined}
        onChange={(event) => props.onChange(event.target.value)}
      />
      {props.error && <FieldError text={props.error} />}
    </div>
  );
}

/** Custom Select with deep magenta left bar, checkmark, and soft pink hover matching the design requirements */
function FormDropdown<T extends string | number>(props: {
  id: string;
  label: string;
  icon?: ReactNode;
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  error?: string;
  required?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLUListElement>(null);

  const currentOption = props.options.find((opt) => opt.value === props.value) ?? props.options[0];

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (open && menuRef.current) {
      const selected = menuRef.current.querySelector<HTMLElement>(".register-dropdown-option--selected");
      if (selected) {
        selected.scrollIntoView({ block: "nearest" });
      }
    }
  }, [open]);

  return (
    <div
      className={`register-field-group ${open ? "register-field-group--open" : ""}`}
      ref={containerRef}
    >
      <label htmlFor={props.id} className="register-field-label">
        {props.icon && <span className="register-field-icon" aria-hidden="true">{props.icon}</span>}
        <span>{props.label}</span>
        {props.required && <span className="register-field-required" aria-hidden="true">*</span>}
      </label>

      {/* Visually hidden native select kept in sync for accessibility and automation */}
      <select
        id={props.id}
        className="visually-hidden"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value as unknown as T)}
        tabIndex={-1}
        aria-hidden="true"
      >
        {props.options.map((opt) => (
          <option key={String(opt.value)} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      <button
        type="button"
        className="register-dropdown-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-invalid={props.error ? true : undefined}
        onClick={() => setOpen(!open)}
      >
        <span className="custom-status-dropdown__label">{currentOption?.label}</span>
        <svg
          className="custom-status-dropdown__arrow"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="m7 9 5 5 5-5" />
        </svg>
      </button>

      {open && (
        <ul ref={menuRef} className="register-dropdown-menu" role="listbox">
          {props.options.map((opt) => {
            const isSelected = props.value === opt.value;
            return (
              <li key={String(opt.value)} role="presentation">
                <button
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  className={
                    isSelected
                      ? "register-dropdown-option register-dropdown-option--selected"
                      : "register-dropdown-option"
                  }
                  onClick={() => {
                    props.onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <span>{opt.label}</span>
                  {isSelected && (
                    <span className="custom-status-dropdown__check" aria-hidden="true">
                      <svg
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        fill="none"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      >
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {props.error && <FieldError text={props.error} />}
    </div>
  );
}

function FieldError({ text }: { text: string }) {
  return (
    <span role="alert" className="party-alert-text">
      {text}
    </span>
  );
}

/* Lucide-style subtle icons */
function CodeIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="16 18 22 12 16 6" />
      <polyline points="8 6 2 12 8 18" />
    </svg>
  );
}

function BuildingIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <rect width="16" height="20" x="4" y="2" rx="2" ry="2" />
      <path d="M9 22v-4h6v4" />
      <path d="M8 6h.01" />
      <path d="M16 6h.01" />
      <path d="M9 10h.01" />
      <path d="M15 10h.01" />
      <path d="M9 14h.01" />
      <path d="M15 14h.01" />
    </svg>
  );
}

function FileTextIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
      <path d="M14 2v4a2 2 0 0 0 2 2h4" />
      <path d="M10 9H8" />
      <path d="M16 13H8" />
      <path d="M16 17H8" />
    </svg>
  );
}

function LanguagesIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <path d="m5 8 6 6" />
      <path d="m4 14 6-6 2-3" />
      <path d="M2 5h12" />
      <path d="M7 2h1" />
      <path d="m22 22-5-10-5 10" />
      <path d="M14 18h6" />
    </svg>
  );
}

function HashIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <line x1="4" x2="20" y1="9" y2="9" />
      <line x1="4" x2="20" y1="15" y2="15" />
      <line x1="10" x2="8" y1="3" y2="21" />
      <line x1="16" x2="14" y1="3" y2="21" />
    </svg>
  );
}

function PercentIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <line x1="19" x2="5" y1="5" y2="19" />
      <circle cx="6.5" cy="6.5" r="2.5" />
      <circle cx="17.5" cy="17.5" r="2.5" />
    </svg>
  );
}

function MapPinIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
      <circle cx="12" cy="10" r="3" />
    </svg>
  );
}

function GlobeIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20" />
      <path d="M2 12h20" />
    </svg>
  );
}

function CalendarIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 2v4" />
      <path d="M16 2v4" />
      <rect width="18" height="18" x="3" y="4" rx="2" />
      <path d="M3 10h18" />
    </svg>
  );
}
