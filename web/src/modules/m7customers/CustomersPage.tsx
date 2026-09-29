import { useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
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

  const [query, setQuery] = useState({ q: "", phone: "" });
  const [search, setSearch] = useState({ q: "", phone: "" });
  const [form, setForm] = useState<Form>(EMPTY);
  const [consent, setConsent] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const set = (patch: Partial<Form>) => setForm((current) => ({ ...current, ...patch }));

  const found = useQuery({
    queryKey: ["customers", "search", search.q, search.phone],
    queryFn: () => api.search(search.q, search.phone)
  });
  const similar = useQuery({
    queryKey: ["customers", "search", form.displayName.trim(), ""],
    queryFn: () => api.search(form.displayName.trim(), ""),
    enabled: form.displayName.trim().length >= 3
  });

  const register = useMutation({
    mutationFn: () => {
      const request: RegisterCustomerRequest = {
        displayName: form.displayName.trim(),
        displayNameSi: form.displayNameSi.trim() || null,
        displayNameTa: form.displayNameTa.trim() || null,
        language: form.language,
        phone: form.phone,
        consents: form.statements ? ["CREDIT_ACCOUNT", "STATEMENTS_NOTIFICATIONS"] : ["CREDIT_ACCOUNT"],
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
    setSearch({ q: query.q.trim(), phone: query.phone.trim() });
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    register.mutate();
  };

  return (
    <main className="shell-page">
      <h1>{t("customers.title").text}</h1>
      {canSeePrivacy && (
        <p>
          <Link to="/customers/privacy">{t("customers.privacy.title").text}</Link>
        </p>
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
        <button type="submit">{t("customers.search.find").text}</button>
      </form>

      <section className="customers-section">
        <h2>{t("customers.list.title").text}</h2>
        {found.isLoading && <p>{t("customers.loading").text}</p>}
        {found.isError && <p role="alert">{errorText(found.error, t("customers.error.generic").text)}</p>}
        {found.data?.length === 0 && <p>{t("customers.list.empty").text}</p>}
        {found.data && found.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>{t("customers.column.name").text}</th>
                <th>{t("customers.column.phone").text}</th>
                <th>{t("customers.column.account").text}</th>
                <th>{t("customers.column.balance").text}</th>
                <th>{t("customers.column.status").text}</th>
              </tr>
            </thead>
            <tbody>
              {found.data.map((customer) => {
                const name = nameIn(locale, customer);
                return (
                  <tr key={customer.customerId}>
                    <td>
                      <Link to={`/customers/${customer.customerId}`}>{name.text}</Link>
                      {name.isFallback && <LangFallbackTag />}
                    </td>
                    <td>{customer.phone}</td>
                    <td>{customer.accountNo ?? t("customers.account.none").text}</td>
                    <td>{customer.balance != null && <MoneyDisplay amount={customer.balance} />}</td>
                    <td>
                      <StateChip state={statusChip(customer.status)} label={t(`customers.status.${customer.status}`).text} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>

      {canRegister ? (
        <section className="customers-section">
          <h2>{t("customers.register.title").text}</h2>
          <form className="customers-form" onSubmit={submit}>
            <label className="customers-field">
              {t("customers.field.name").text}
              <input required maxLength={120} value={form.displayName} onChange={(e) => set({ displayName: e.target.value })} />
            </label>
            {similar.data && similar.data.length > 0 && (
              <p role="note">
                {t("customers.register.similar").text}{" "}
                {similar.data.slice(0, 5).map((customer) => (
                  <Link key={customer.customerId} className="customers-similar" to={`/customers/${customer.customerId}`}>
                    {nameIn(locale, customer).text} ({customer.phone})
                  </Link>
                ))}
              </p>
            )}
            <label className="customers-field">
              {t("customers.field.name_si").text}
              <input lang="si" maxLength={120} value={form.displayNameSi} onChange={(e) => set({ displayNameSi: e.target.value })} />
            </label>
            <label className="customers-field">
              {t("customers.field.name_ta").text}
              <input lang="ta" maxLength={120} value={form.displayNameTa} onChange={(e) => set({ displayNameTa: e.target.value })} />
            </label>
            <label className="customers-field">
              {t("customers.field.phone").text}
              <input
                required
                type="tel"
                maxLength={20}
                value={form.phone}
                onChange={(e) => {
                  set({ phone: e.target.value });
                  setConfirmed(false);
                }}
              />
            </label>
            <label className="customers-field">
              {t("customers.field.language").text}
              <select
                aria-label={t("customers.field.language").text}
                value={form.language}
                onChange={(e) => set({ language: e.target.value as Form["language"] })}
              >
                <option value="si">{t("customers.language.si").text}</option>
                <option value="ta">{t("customers.language.ta").text}</option>
                <option value="en">{t("customers.language.en").text}</option>
              </select>
            </label>
            <label className="customers-field">
              {t("customers.field.tags").text}
              <input maxLength={200} value={form.tags} onChange={(e) => set({ tags: e.target.value })} />
            </label>
            <label className="customers-check">
              <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              {t("customers.consent.credit").text}
            </label>
            <label className="customers-check">
              <input type="checkbox" checked={form.statements} onChange={(e) => set({ statements: e.target.checked })} />
              {t("customers.consent.statements").text}
            </label>
            {needsConfirmation && (
              <label className="customers-check customers-confirm">
                <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                {t("customers.register.confirm_identity").text}
              </label>
            )}
            <button
              type="submit"
              disabled={register.isPending || !consent || !form.displayName.trim() || !form.phone.trim() || (needsConfirmation && !confirmed)}
            >
              {t("customers.register.submit").text}
            </button>
            {register.isError && <p role="alert">{errorText(register.error, t("customers.error.generic").text)}</p>}
          </form>
        </section>
      ) : (
        <p role="note">{t("customers.read_only").text}</p>
      )}
    </main>
  );
}
