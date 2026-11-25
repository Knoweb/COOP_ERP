import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { useT } from "../../shell/i18n/useT";
import { useCustomersApi, type RegisterCustomerRequest } from "./customersApi";
import { PHONE_REUSE_CONFIRM, errorText, nameIn, problemCode, statusChip } from "./customersView";
import "./customers.css";

type Form = {
  displayName: string;
  displayNameSi: string;
  displayNameTa: string;
  language: "si" | "ta" | "en";
  phone: string;
  statements: boolean;
  tags: string;
};

const EMPTY: Form = { displayName: "", displayNameSi: "", displayNameTa: "", language: "si", phone: "", statements: false, tags: "" };

function LanguageDropdown({
  label,
  value,
  onChange
}: {
  label: string;
  value: Form["language"];
  onChange: (val: Form["language"]) => void;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const t = useT();

  const options: { value: Form["language"]; label: string }[] = [
    { value: "si", label: t("customers.language.si").text },
    { value: "ta", label: t("customers.language.ta").text },
    { value: "en", label: t("customers.language.en").text }
  ];

  const currentOption = options.find((opt) => opt.value === value) ?? options[0];

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

  return (
    <div className="customers-field customers-dropdown-field">
      <span className="customers-field-caption customers-field-label">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
        <span>{label}</span>
      </span>
      <div className="custom-status-dropdown customers-custom-dropdown" ref={containerRef}>
        <button
          type="button"
          className="custom-status-dropdown__trigger"
          aria-haspopup="listbox"
          aria-expanded={open}
          onClick={() => setOpen((prev) => !prev)}
        >
          <span className="custom-status-dropdown__label">{currentOption.label}</span>
          <svg className="custom-status-dropdown__arrow" viewBox="0 0 24 24" aria-hidden="true">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>

        {open && (
          <ul className="custom-status-dropdown__menu" role="listbox">
            {options.map((opt) => {
              const isSelected = value === opt.value;
              return (
                <li key={opt.value} role="presentation">
                  <button
                    type="button"
                    role="option"
                    aria-selected={isSelected}
                    className={
                      isSelected
                        ? "custom-status-dropdown__option custom-status-dropdown__option--selected"
                        : "custom-status-dropdown__option"
                    }
                    onClick={() => {
                      onChange(opt.value);
                      setOpen(false);
                    }}
                  >
                    <span className="custom-status-dropdown__option-label">{opt.label}</span>
                    {isSelected && (
                      <span className="custom-status-dropdown__check" aria-hidden="true">
                        <svg
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2.5"
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
      </div>

      <select
        aria-label={label}
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        value={value}
        onChange={(e) => onChange(e.target.value as Form["language"])}
      >
        <option value="si">{t("customers.language.si").text}</option>
        <option value="ta">{t("customers.language.ta").text}</option>
        <option value="en">{t("customers.language.en").text}</option>
      </select>
    </div>
  );
}

/**
 * The society's member register (27A section 8, "Khata index (lookup)" and "Customer card"
 * registration): find a member by name or phone, and register a new one. Registration asks for
 * the credit-account consent (paper form at the office), and when somebody else held the phone
 * recently the server asks the officer to confirm it is another person (27A section 6.1) before
 * it registers. While typing a name, the members already registered under it are shown, so the
 * officer sees a likely duplicate before registering it.
 */
export function CustomersPage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useCustomersApi();
  const navigate = useNavigate();
  const key = useIdempotencyKey();
  const canRegister = useHasPermission("cus.customer.register");
  const canSeePrivacy = useHasPermission("cus.privacy.record");

  const [query, setQuery] = useState({ q: "", phone: "", memberNo: "" });
  const [search, setSearch] = useState({ q: "", phone: "", memberNo: "" });
  const [form, setForm] = useState<Form>(EMPTY);
  const [consent, setConsent] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const set = (patch: Partial<Form>) => setForm((current) => ({ ...current, ...patch }));

  const found = useQuery({
    queryKey: ["customers", "search", search.q, search.phone, search.memberNo],
    queryFn: async () => {
      if (search.memberNo) {
        const loyalty = await api.lookupLoyalty({ memberNo: search.memberNo });
        if (!loyalty) {
          return [];
        }
        try {
          const card = await api.customer(loyalty.customerId);
          if (card) {
            return [
              {
                customerId: card.customerId,
                displayName: card.displayName,
                displayNameSi: card.displayNameSi,
                displayNameTa: card.displayNameTa,
                language: card.language,
                phone: card.phone,
                status: card.status,
                accountId: card.account?.accountId,
                accountNo: card.account?.accountNo,
                balance: card.account?.balance,
                creditLimit: card.account?.creditLimit
              }
            ];
          }
        } catch {
          // Fallback to loyalty summary
        }
        return [
          {
            customerId: loyalty.customerId,
            displayName: loyalty.displayName ?? "",
            displayNameSi: loyalty.displayNameSi ?? null,
            displayNameTa: loyalty.displayNameTa ?? null,
            language: "en" as const,
            phone: loyalty.phone ?? "",
            status: loyalty.status,
            accountNo: null,
            balance: null,
            creditLimit: null
          }
        ];
      }
      return api.search(search.q, search.phone);
    }
  });
  const similar = useQuery({
    queryKey: ["customers", "search", form.displayName.trim(), ""],
    queryFn: () => api.search(form.displayName.trim(), ""),
    enabled: form.displayName.trim().length >= 3
  });

  const register = useMutation({
    mutationFn: () => {
      const consents: ("CREDIT_ACCOUNT" | "STATEMENTS_NOTIFICATIONS")[] = [];
      if (consent) consents.push("CREDIT_ACCOUNT");
      if (form.statements) consents.push("STATEMENTS_NOTIFICATIONS");
      const request: RegisterCustomerRequest = {
        displayName: form.displayName.trim(),
        displayNameSi: form.displayNameSi.trim() || null,
        displayNameTa: form.displayNameTa.trim() || null,
        language: form.language,
        phone: form.phone,
        consents,
        via: "PAPER",
        tags: form.tags
          .split(",")
          .map((tag) => tag.trim())
          .filter((tag) => tag.length > 0),
        confirmedIdentity: confirmed
      };
      return api.register(request, key.current());
    },
    onSuccess: (card) => {
      key.next();
      navigate(`/customers/${card.customerId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const needsConfirmation = problemCode(register.error) === PHONE_REUSE_CONFIRM;

  const find = (event: FormEvent) => {
    event.preventDefault();
    setSearch({ q: query.q.trim(), phone: query.phone.trim(), memberNo: query.memberNo.trim() });
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    register.mutate();
  };

  return (
    <main className="shell-page customers-page">
      <PageHeader icon="relationships" title={t("customers.title").text} />
      {canSeePrivacy && (
        <div className="customers-top-bar">
          <Link to="/customers/privacy" className="customers-privacy-link">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
            <span>{t("customers.privacy.title").text}</span>
          </Link>
        </div>
      )}

      <form className="customers-filter-bar" onSubmit={find} role="search">
        <label className="customers-field">
          {t("customers.search.name").text}
          <input type="search" maxLength={100} value={query.q} onChange={(e) => setQuery({ ...query, q: e.target.value })} />
        </label>
        <label className="customers-field">
          {t("customers.search.phone").text}
          <input type="tel" maxLength={20} value={query.phone} onChange={(e) => setQuery({ ...query, phone: e.target.value })} />
        </label>
        <label className="customers-field">
          {t("customers.search.member_no").text}
          <input
            type="search"
            maxLength={60}
            value={query.memberNo}
            onChange={(e) => setQuery({ ...query, memberNo: e.target.value })}
          />
        </label>
        <button type="submit" className="customers-filter-btn">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <span>{t("customers.search.find").text}</span>
        </button>
      </form>

      <section className="customers-section customers-section-card">
        <div className="customers-section-header">
          <div className="customers-section-title-wrap">
            <span className="customers-section-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
            </span>
            <h2>{t("customers.list.title").text}</h2>
          </div>
          {found.data && found.data.length > 0 && (
            <span className="customers-badge-count">
              {found.data.length}
            </span>
          )}
        </div>

        {found.isLoading && (
          <div className="customers-state-box">
            <p>{t("customers.loading").text}</p>
          </div>
        )}
        {found.isError && (
          <div className="customers-state-box customers-state-box--error" role="alert">
            <p>{errorText(found.error, t("customers.error.generic").text)}</p>
          </div>
        )}
        {found.data?.length === 0 && (
          <div className="customers-state-box customers-state-box--empty">
            <p>{search.memberNo ? t("customers.loyalty.not_found").text : t("customers.list.empty").text}</p>
          </div>
        )}
        {found.data && found.data.length > 0 && (
          <div className="modern-table-card customers-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table customers-data-table">
                <thead>
                  <tr>
                    <th>{t("customers.column.name").text}</th>
                    <th>{t("customers.column.phone").text}</th>
                    <th>{t("customers.column.account").text}</th>
                    <th className="customers-th-right">{t("customers.column.balance").text}</th>
                    <th className="customers-th-center">{t("customers.column.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {found.data.map((customer) => {
                    const name = nameIn(locale, customer, t("customers.erased_member").text);
                    return (
                      <tr key={customer.customerId}>
                        <td className="customers-name-cell">
                          <Link to={`/customers/${customer.customerId}`} className="customers-member-link">
                            {name.text}
                          </Link>
                          {name.isFallback && <LangFallbackTag />}
                        </td>
                        <td className="customers-phone-cell">{customer.phone}</td>
                        <td className="customers-code-cell">{customer.accountNo ?? t("customers.account.none").text}</td>
                        <td className="customers-td-right">
                          {customer.balance != null ? <strong><MoneyDisplay amount={customer.balance} /></strong> : "—"}
                        </td>
                        <td className="customers-td-center">
                          <StateChip state={statusChip(customer.status)} label={t(`customers.status.${customer.status}`).text} />
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

      {canRegister ? (
        <section className="customers-section customers-section-card customers-register-card">
          <div className="customers-section-header">
            <div className="customers-section-title-wrap">
              <span className="customers-section-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                  <circle cx="8.5" cy="7" r="4" />
                  <line x1="20" y1="8" x2="20" y2="14" />
                  <line x1="23" y1="11" x2="17" y2="11" />
                </svg>
              </span>
              <h2>{t("customers.register.title").text}</h2>
            </div>
          </div>

          <form className="customers-form customers-register-form" onSubmit={submit}>
            <div className="customers-form-row customers-form-row--3col customers-form-row--names">
              <label className="customers-field">
                <span className="customers-field-label">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                    <circle cx="12" cy="7" r="4" />
                  </svg>
                  <span>{t("customers.field.name").text}</span>
                </span>
                <input
                  required
                  maxLength={120}
                  aria-label={t("customers.field.name").text}
                  value={form.displayName}
                  onChange={(e) => set({ displayName: e.target.value })}
                />
              </label>
              <label className="customers-field">
                <span className="customers-field-label">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <circle cx="12" cy="12" r="10" />
                    <line x1="2" y1="12" x2="22" y2="12" />
                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                  </svg>
                  <span>{t("customers.field.name_si").text}</span>
                </span>
                <input
                  lang="si"
                  maxLength={120}
                  aria-label={t("customers.field.name_si").text}
                  value={form.displayNameSi}
                  onChange={(e) => set({ displayNameSi: e.target.value })}
                />
              </label>
              <label className="customers-field">
                <span className="customers-field-label">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <circle cx="12" cy="12" r="10" />
                    <line x1="2" y1="12" x2="22" y2="12" />
                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                  </svg>
                  <span>{t("customers.field.name_ta").text}</span>
                </span>
                <input
                  lang="ta"
                  maxLength={120}
                  aria-label={t("customers.field.name_ta").text}
                  value={form.displayNameTa}
                  onChange={(e) => set({ displayNameTa: e.target.value })}
                />
              </label>
            </div>

            {similar.data && similar.data.length > 0 && (
              <div className="customers-similar-callout" role="note">
                <span className="customers-similar-callout__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="10" />
                    <line x1="12" y1="8" x2="12" y2="12" />
                    <line x1="12" y1="16" x2="12.01" y2="16" />
                  </svg>
                </span>
                <div className="customers-similar-callout__body">
                  <p>
                    {t("customers.register.similar").text}{" "}
                    {similar.data.slice(0, 5).map((customer) => (
                      <Link key={customer.customerId} className="customers-similar" to={`/customers/${customer.customerId}`}>
                        {nameIn(locale, customer, t("customers.erased_member").text).text} ({customer.phone})
                      </Link>
                    ))}
                  </p>
                </div>
              </div>
            )}

            <div className="customers-form-row customers-form-row--details">
              <label className="customers-field">
                <span className="customers-field-label">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                  </svg>
                  <span>{t("customers.field.phone").text}</span>
                </span>
                <input
                  required
                  type="tel"
                  maxLength={20}
                  aria-label={t("customers.field.phone").text}
                  value={form.phone}
                  onChange={(e) => {
                    set({ phone: e.target.value });
                    setConfirmed(false);
                  }}
                />
              </label>
              <LanguageDropdown
                label={t("customers.field.language").text}
                value={form.language}
                onChange={(lang) => set({ language: lang })}
              />
              <label className="customers-field customers-field--tags">
                <span className="customers-field-label">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
                    <line x1="7" y1="7" x2="7.01" y2="7" />
                  </svg>
                  <span>{t("customers.field.tags").text}</span>
                </span>
                <input
                  maxLength={200}
                  aria-label={t("customers.field.tags").text}
                  value={form.tags}
                  onChange={(e) => set({ tags: e.target.value })}
                />
              </label>
            </div>

            <div className="customers-consents-card">
              <div className="customers-consents-grid">
                <label className="customers-check">
                  <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
                  <span>{t("customers.consent.credit").text}</span>
                </label>
                <label className="customers-check">
                  <input type="checkbox" checked={form.statements} onChange={(e) => set({ statements: e.target.checked })} />
                  <span>{t("customers.consent.statements").text}</span>
                </label>
              </div>
            </div>

            {needsConfirmation && (
              <div className="customers-confirm-callout">
                <label className="customers-check customers-confirm">
                  <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                  <span>{t("customers.register.confirm_identity").text}</span>
                </label>
              </div>
            )}

            <div className="customers-form-actions">
              <button
                type="submit"
                className="customers-btn-primary"
                disabled={register.isPending || !form.displayName.trim() || !form.phone.trim() || (needsConfirmation && !confirmed)}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                  <circle cx="8.5" cy="7" r="4" />
                  <line x1="20" y1="8" x2="20" y2="14" />
                  <line x1="23" y1="11" x2="17" y2="11" />
                </svg>
                <span>{t("customers.register.submit").text}</span>
              </button>
            </div>

            {register.isError && (
              <div className="customers-state-box customers-state-box--error" role="alert">
                <p>{errorText(register.error, t("customers.error.generic").text)}</p>
              </div>
            )}
          </form>
        </section>
      ) : (
        <p role="note" className="customers-read-only-note">{t("customers.read_only").text}</p>
      )}
    </main>
  );
}
