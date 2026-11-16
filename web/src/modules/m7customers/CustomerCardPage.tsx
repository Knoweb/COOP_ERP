import { useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { inLocale } from "../../shell/i18n/localName";
import { useT } from "../../shell/i18n/useT";
import { AccountManagement } from "./AccountManagement";
import { CustomSelect } from "./CustomSelect";
import {
  useCustomersApi,
  type Account,
  type CustomerCard,
  type CustomerPayment,
  type Location,
  type LoyaltyCustomerResponse,
  type LoyaltyEligibilityResponse
} from "./customersApi";
import { PrivacyRequestForm } from "./PrivacyRequestsPage";
import { errorText, limitUsedPercent, nameIn, statusChip } from "./customersView";
import "./customers.css";

/**
 * The customer card and khata page of the society office (27A section 8): who the member is
 * (phone and its history, NIC's last four, consents, tags), the society's credit account (limit,
 * balance, available, ageing) and the two things the office does with it: open the account, and
 * record a repayment, which settles the oldest charges first and prints as a CPR.
 */
export function CustomerCardPage() {
  const { customerId = "" } = useParams();
  const t = useT();
  const { locale } = useIntl();
  const api = useCustomersApi();
  const formatInstant = useFormatInstant();
  const card = useQuery({ queryKey: ["customers", "card", customerId], queryFn: () => api.customer(customerId) });
  const loyalty = useQuery({ queryKey: ["customers", "loyalty", customerId], queryFn: () => api.lookupLoyalty({ customerId }) });
  const locations = useQuery({ queryKey: ["party", "locations"], queryFn: () => api.locations() });
  const canRecordPrivacy = useHasPermission("cus.privacy.record");
  const canRegisterCustomer = useHasPermission("cus.customer.register");
  const canManageCustomer = useHasPermission("cus.customer.manage");

  if (card.isLoading) {
    return (
      <main className="shell-page">
        <p>{t("customers.loading").text}</p>
      </main>
    );
  }
  if (card.isError || !card.data) {
    return (
      <main className="shell-page">
        <BackLink />
        <p role="alert">{card.isError ? errorText(card.error, t("customers.error.generic").text) : t("customers.error.not_found").text}</p>
      </main>
    );
  }
  const customer = card.data;
  const name = nameIn(locale, customer, t("customers.erased_member").text);

  return (
    <main className="shell-page customers-page">
      <BackLink />
      <PageHeader
        icon="relationships"
        title={
          <>
            {name.text}
            {name.isFallback && <LangFallbackTag />}
          </>
        }
      />
      {customer.status === "ANONYMISED" && (
        <div className="customers-erased-callout" role="note">
          <div className="customers-erased-callout__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
          </div>
          <div className="customers-erased-callout__body">
            <div className="customers-erased-callout__header">
              <strong className="customers-erased-callout__title">{t("customers.erased_member").text}</strong>
              <StateChip state="void" label={t("customers.status.ANONYMISED").text} />
            </div>
            <p className="customers-erased-callout__desc">
              {t("customers.privacy.erasure_warning").text}
            </p>
          </div>
        </div>
      )}
      <section className={`customers-section customers-section-card customer-profile-card${customer.status === "ANONYMISED" ? " customer-profile-card--anonymised" : ""}`}>
        <div className="customers-section-header">
          <div className="customers-section-title-wrap">
            <span className="customers-section-icon" aria-hidden="true">
              {customer.status === "ANONYMISED" ? (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                  <circle cx="8.5" cy="7" r="4" />
                  <line x1="18" y1="8" x2="23" y2="13" />
                  <line x1="23" y1="8" x2="18" y2="13" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
              )}
            </span>
            <h2>{name.text}</h2>
          </div>
          <StateChip state={statusChip(customer.status)} label={t(`customers.status.${customer.status}`).text} />
        </div>
        <dl className="customers-facts">
          <dt>{t("customers.column.phone").text}</dt>
          <dd>{customer.phone || "—"}</dd>
          <dt>{t("customers.field.language").text}</dt>
          <dd>{t(`customers.language.${customer.language}`).text}</dd>
          <dt>{t("customers.card.nic").text}</dt>
          <dd>{customer.nicLast4 ? `****${customer.nicLast4}` : t("customers.card.nic_none").text}</dd>
          <dt>{t("customers.column.status").text}</dt>
          <dd>
            <StateChip state={statusChip(customer.status)} label={t(`customers.status.${customer.status}`).text} />
          </dd>
          <dt>{t("customers.card.consents").text}</dt>
          <dd>
            {customer.consents
              .filter((consent) => !consent.withdrawnAt)
              .map((consent) => t(`customers.consent.purpose.${consent.purpose}`).text)
              .join(", ") || "—"}
          </dd>
          <dt>{t("customers.field.tags").text}</dt>
          <dd>{customer.tags.length > 0 ? customer.tags.join(", ") : "—"}</dd>
          <dt>{t("customers.card.registered").text}</dt>
          <dd>{formatInstant(customer.registeredAt)}</dd>
        </dl>
      </section>

      {customer.phones.length > 1 && (
        <section className="customers-section customers-section-card">
          <div className="customers-section-header">
            <div className="customers-section-title-wrap">
              <span className="customers-section-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                </svg>
              </span>
              <h2>{t("customers.card.phone_history").text}</h2>
            </div>
          </div>
          <ul className="customers-list customer-phone-history-list">
            {customer.phones.map((phone) => (
              <li key={phone.validFrom} className="customer-phone-history-item">
                <span className="customer-phone-number">{phone.phone}</span>
                <span className="customer-phone-dates">
                  {formatInstant(phone.validFrom)}
                  {phone.validTo && <> – {formatInstant(phone.validTo)}</>}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {(customer.status !== "ANONYMISED" || loyalty.data) && (
        <MembershipLoyaltySection
          customer={customer}
          loyalty={loyalty.data}
          locations={locations.data ?? []}
          isLoading={loyalty.isLoading}
          canRegister={canRegisterCustomer}
          canManage={canManageCustomer}
        />
      )}

      {customer.account ? (
        <AccountPanel account={customer.account} customerId={customerId} />
      ) : (
        customer.status === "ACTIVE" && <OpenAccountForm customerId={customerId} />
      )}
      {canRecordPrivacy && customer.registeredHere && customer.status !== "ANONYMISED" && (
        <PrivacyRequestForm customerId={customerId} />
      )}
    </main>
  );
}

function BackLink() {
  const t = useT();
  return (
    <Link className="back-link back-pill-btn customers-back-link" to="/customers">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <line x1="19" y1="12" x2="5" y2="12" />
        <polyline points="12 19 5 12 12 5" />
      </svg>
      <span>{t("customers.back").text}</span>
    </Link>
  );
}

/** The account at the caller's society: limit, balance, what is left, the ageing, and a repayment. */
function AccountPanel({ account, customerId }: { account: Account; customerId: string }) {
  const t = useT();
  const formatDate = useFormatDate();
  const canRecord = useHasPermission("cus.payment.record");
  const used = limitUsedPercent(account.creditLimit, account.balance);

  return (
    <section className="customers-section customers-section-card" aria-labelledby="customers-account-title">
      <div className="customers-section-header">
        <div className="customers-section-title-wrap">
          <span className="customers-section-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="1" y="4" width="22" height="16" rx="2" ry="2" />
              <line x1="1" y1="10" x2="23" y2="10" />
            </svg>
          </span>
          <h2 id="customers-account-title">
            {t("customers.account.title").text}{" "}
            <span className="customers-account-no">{account.accountNo}</span>
          </h2>
        </div>
        <StateChip state={statusChip(account.status)} label={t(`customers.account.status.${account.status}`).text} />
      </div>
      <dl className="customers-facts">
        <dt>{t("customers.account.limit").text}</dt>
        <dd>
          <MoneyDisplay amount={account.creditLimit} />
        </dd>
        <dt>{t("customers.account.balance").text}</dt>
        <dd>
          <MoneyDisplay amount={account.balance} />
        </dd>
        <dt>{t("customers.account.available").text}</dt>
        <dd>
          <MoneyDisplay amount={account.available} />
        </dd>
        <dt>{t("customers.account.offline_cap").text}</dt>
        <dd>{account.offlineCap != null ? <MoneyDisplay amount={account.offlineCap} /> : "—"}</dd>
        <dt>{t("customers.account.oldest_unpaid").text}</dt>
        <dd>{account.oldestUnpaid ? formatDate(account.oldestUnpaid) : "—"}</dd>
        {account.unallocated > 0 && (
          <>
            <dt>{t("customers.account.unallocated").text}</dt>
            <dd>
              <MoneyDisplay amount={account.unallocated} />
            </dd>
          </>
        )}
      </dl>
      {used >= 80 && (
        <div role="note" className="customers-warning-card">
          <StateChip state="alert" label={t("customers.account.near_limit", undefined, { percent: used }).text} />
        </div>
      )}
      <div className="modern-table-card customers-table-card customers-ageing-card">
        <div className="modern-table-scroll">
          <table className="modern-table customers-data-table customers-ageing-table">
            <caption>{t("customers.account.ageing").text}</caption>
            <thead>
              <tr>
                <th className="customers-th-right">{t("customers.ageing.0_30").text}</th>
                <th className="customers-th-right">{t("customers.ageing.31_60").text}</th>
                <th className="customers-th-right">{t("customers.ageing.61_90").text}</th>
                <th className="customers-th-right">{t("customers.ageing.over_90").text}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="customers-td-right">
                  <MoneyDisplay amount={account.ageing.days0To30} />
                </td>
                <td className="customers-td-right">
                  <MoneyDisplay amount={account.ageing.days31To60} />
                </td>
                <td className="customers-td-right">
                  <MoneyDisplay amount={account.ageing.days61To90} />
                </td>
                <td className="customers-td-right">
                  <MoneyDisplay amount={account.ageing.over90} />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div className="customers-statement-link-wrap">
        <Link to={`/customers/${customerId}/accounts/${account.accountId}/statement`} className="customers-statement-link">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
            <polyline points="10 9 9 9 8 9" />
          </svg>
          <span>{t("customers.statement.open").text}</span>
        </Link>
      </div>
      {canRecord && account.status !== "CLOSED" && <RepaymentForm account={account} customerId={customerId} />}
      <AccountManagement account={account} customerId={customerId} />
    </section>
  );
}

/** Open the society's account: a limit above zero needs the NIC, of which only the last four are kept. */
function OpenAccountForm({ customerId }: { customerId: string }) {
  const t = useT();
  const api = useCustomersApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const canOpen = useHasPermission("cus.account.manage");
  const [limit, setLimit] = useState("");
  const [nic, setNic] = useState("");
  const open = useMutation({
    mutationFn: () =>
      api.openAccount(customerId, { creditLimit: Number(limit || "0"), nic: nic.trim() || null }, key.current()),
    onSuccess: () => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["customers"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  if (!canOpen) {
    return <p>{t("customers.account.none").text}</p>;
  }
  const submit = (event: FormEvent) => {
    event.preventDefault();
    open.mutate();
  };
  return (
    <section className="customers-section customers-section-card">
      <div className="customers-section-header">
        <div className="customers-section-title-wrap">
          <span className="customers-section-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="1" y="4" width="22" height="16" rx="2" ry="2" />
              <line x1="1" y1="10" x2="23" y2="10" />
            </svg>
          </span>
          <h2>{t("customers.account.open.title").text}</h2>
        </div>
      </div>
      <form className="customers-form" onSubmit={submit}>
        <div className="customers-form-row customers-form-row--2col">
          <label className="customers-field">
            <span className="customers-field-label">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10" />
                <path d="M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8" />
                <path d="M12 18V6" />
              </svg>
              <span>{t("customers.account.limit").text}</span>
            </span>
            <input
              type="number"
              min="0"
              step="0.01"
              required
              aria-label={t("customers.account.limit").text}
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
            />
          </label>
          <label className="customers-field">
            <span className="customers-field-label">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="3" y="4" width="18" height="16" rx="2" />
                <circle cx="9" cy="10" r="2" />
                <line x1="15" y1="8" x2="17" y2="8" />
                <line x1="15" y1="12" x2="17" y2="12" />
                <line x1="7" y1="16" x2="17" y2="16" />
              </svg>
              <span>{t("customers.account.nic").text}</span>
            </span>
            <input
              maxLength={20}
              autoComplete="off"
              aria-label={t("customers.account.nic").text}
              value={nic}
              onChange={(e) => setNic(e.target.value)}
            />
          </label>
        </div>
        <div className="customers-form-actions">
          <button type="submit" className="customers-btn-primary" disabled={open.isPending || limit === ""}>
            <span>{t("customers.account.open.submit").text}</span>
          </button>
        </div>
        {open.isError && <p role="alert">{errorText(open.error, t("customers.error.generic").text)}</p>}
      </form>
    </section>
  );
}

const METHODS = ["CASH", "TRANSFER", "DEPOSIT"] as const;

/** A repayment at the office (27A section 8, "Repayment"): amount, method, reference; oldest charges first. */
function RepaymentForm({ account, customerId }: { account: Account; customerId: string }) {
  const t = useT();
  const api = useCustomersApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState<(typeof METHODS)[number]>("CASH");
  const [reference, setReference] = useState("");
  const [recorded, setRecorded] = useState<CustomerPayment | null>(null);
  const record = useMutation({
    mutationFn: () =>
      api.recordPayment(
        account.accountId,
        { method, amount: Number(amount), reference: reference.trim() || null, allocationMode: "OLDEST_FIRST" },
        key.current()
      ),
    onSuccess: (payment) => {
      key.next();
      setRecorded(payment);
      setAmount("");
      setReference("");
      queryClient.invalidateQueries({ queryKey: ["customers", "card", customerId] });
      queryClient.invalidateQueries({ queryKey: ["customers", "statement", account.accountId] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    record.mutate();
  };
  return (
    <form className="customers-form customers-subcard" onSubmit={submit} aria-labelledby="customers-repayment-title">
      <h3 id="customers-repayment-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <path d="M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8" />
          <path d="M12 18V6" />
        </svg>
        <span>{t("customers.payment.title").text}</span>
      </h3>
      <p className="customers-hint-text">{t("customers.payment.oldest_first").text}</p>
      <div className="customers-form-row customers-form-row--3col">
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <path d="M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8" />
              <path d="M12 18V6" />
            </svg>
            <span>{t("customers.payment.amount").text}</span>
          </span>
          <input
            type="number"
            min="0.01"
            step="0.01"
            required
            aria-label={t("customers.payment.amount").text}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="customer-payment-method">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="1" y="4" width="22" height="16" rx="2" ry="2" />
              <line x1="1" y1="10" x2="23" y2="10" />
            </svg>
            <span>{t("customers.payment.method").text}</span>
          </label>
          <CustomSelect
            id="customer-payment-method"
            ariaLabel={t("customers.payment.method").text}
            value={method}
            onChange={(val) => setMethod(val as (typeof METHODS)[number])}
            options={METHODS.map((each) => ({
              value: each,
              label: t(`customers.payment.method.${each}`).text
            }))}
          />
        </div>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
            </svg>
            <span>{t("customers.payment.reference").text}</span>
          </span>
          <input
            maxLength={100}
            aria-label={t("customers.payment.reference").text}
            value={reference}
            onChange={(e) => setReference(e.target.value)}
          />
        </label>
      </div>
      <div className="customers-form-actions">
        <button type="submit" className="customers-btn-primary" disabled={record.isPending || !(Number(amount) > 0)}>
          <span>{t("customers.payment.record").text}</span>
        </button>
      </div>
      {record.isError && <p role="alert">{errorText(record.error, t("customers.error.generic").text)}</p>}
      {recorded && (
        <p role="status">
          {t("customers.payment.recorded", undefined, { number: recorded.docNumber }).text} <MoneyDisplay amount={recorded.amount} />
        </p>
      )}
    </form>
  );
}

const TIERS = ["STANDARD", "SILVER", "GOLD", "PLATINUM", "MEMBER"] as const;
const LOYALTY_STATUSES = ["ACTIVE", "SUSPENDED", "INACTIVE"] as const;

type LoyaltySectionProps = {
  customer: CustomerCard;
  loyalty: LoyaltyCustomerResponse | null | undefined;
  locations: Location[];
  isLoading: boolean;
  canRegister: boolean;
  canManage: boolean;
};

function MembershipLoyaltySection({
  customer,
  loyalty,
  locations,
  isLoading,
  canRegister,
  canManage
}: LoyaltySectionProps) {
  const t = useT();
  const { locale } = useIntl();
  const queryClient = useQueryClient();

  const registeredLocation = locations.find((l) => l.locationId === loyalty?.registeredLocationId);
  const locationLabel = (loc: Location) => inLocale(locale, loc.nameEn, loc.nameSi, loc.nameTa) || loc.locationCode;

  return (
    <section className="customers-section customers-section-card" aria-labelledby="customers-loyalty-title">
      <div className="customers-section-header">
        <div className="customers-section-title-wrap">
          <span className="customers-section-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
          </span>
          <h2 id="customers-loyalty-title">{t("customers.loyalty.title").text}</h2>
        </div>
      </div>
      {isLoading && <p>{t("customers.loading").text}</p>}
      {!isLoading && !loyalty && (
        <>
          <p>{t("customers.loyalty.not_enrolled").text}</p>
          {canRegister && customer.status === "ACTIVE" && (
            <EnrollLoyaltyForm
              customer={customer}
              locations={locations}
              onSuccess={() => {
                queryClient.invalidateQueries({ queryKey: ["customers", "loyalty", customer.customerId] });
              }}
            />
          )}
        </>
      )}
      {!isLoading && loyalty && (
        <>
          <dl className="customers-facts">
            <dt>{t("customers.loyalty.regional_member_no").text}</dt>
            <dd>{loyalty.regionalMemberNo || "—"}</dd>

            <dt>{t("customers.loyalty.is_member").text}</dt>
            <dd>
              <StateChip
                state={loyalty.isMember ? "issued" : "draft"}
                label={loyalty.isMember ? t("customers.loyalty.is_member_yes").text : t("customers.loyalty.is_member_no").text}
              />
            </dd>

            <dt>{t("customers.loyalty.tier").text}</dt>
            <dd>
              <StateChip state="issued" label={t(`customers.loyalty.tier.${loyalty.loyaltyTier}`).text} />
            </dd>

            <dt>{t("customers.loyalty.status").text}</dt>
            <dd>
              <StateChip state={statusChip(loyalty.status)} label={t(`customers.loyalty.status.${loyalty.status}`).text} />
            </dd>

            <dt>{t("customers.loyalty.region_code").text}</dt>
            <dd>{loyalty.regionCode}</dd>

            <dt>{t("customers.loyalty.registered_location").text}</dt>
            <dd>{registeredLocation ? locationLabel(registeredLocation) : loyalty.registeredLocationId}</dd>

            <dt>{t("customers.loyalty.branch_scope").text}</dt>
            <dd>
              {loyalty.allLocationsPermitted
                ? t("customers.loyalty.scope.all_locations").text
                : t("customers.loyalty.scope.selected_locations", undefined, {
                    count: loyalty.permittedLocationIds?.length ?? 0
                  }).text}
              {!loyalty.allLocationsPermitted && loyalty.permittedLocationIds && loyalty.permittedLocationIds.length > 0 && (
                <div style={{ marginTop: "var(--space-half)" }}>
                  {loyalty.permittedLocationIds
                    .map((id) => {
                      const loc = locations.find((l) => l.locationId === id);
                      return loc ? locationLabel(loc) : id;
                    })
                    .join(", ")}
                </div>
              )}
            </dd>
          </dl>

          {canManage && (
            <UpdateLoyaltyForm
              loyalty={loyalty}
              locations={locations}
              onSuccess={() => {
                queryClient.invalidateQueries({ queryKey: ["customers", "loyalty", customer.customerId] });
              }}
            />
          )}

          <LoyaltyEligibilityChecker
            customer={customer}
            loyalty={loyalty}
            locations={locations}
          />
        </>
      )}
    </section>
  );
}

function EnrollLoyaltyForm({
  customer,
  locations,
  onSuccess
}: {
  customer: CustomerCard;
  locations: Location[];
  onSuccess: () => void;
}) {
  const t = useT();
  const api = useCustomersApi();
  const key = useIdempotencyKey();
  const { locale } = useIntl();
  const locationLabel = (loc: Location) => inLocale(locale, loc.nameEn, loc.nameSi, loc.nameTa) || loc.locationCode;

  const [regionalMemberNo, setRegionalMemberNo] = useState("");
  const [regionCode, setRegionCode] = useState("");
  const [loyaltyTier, setLoyaltyTier] = useState<(typeof TIERS)[number]>("STANDARD");
  const [registeredLocationId, setRegisteredLocationId] = useState(locations[0]?.locationId ?? "");
  const [allLocationsPermitted, setAllLocationsPermitted] = useState(true);
  const [permittedLocationIds, setPermittedLocationIds] = useState<string[]>([]);

  const enroll = useMutation({
    mutationFn: () =>
      api.registerLoyalty(
        {
          customerId: customer.customerId,
          regionalMemberNo: regionalMemberNo.trim() || null,
          regionCode: regionCode.trim(),
          loyaltyTier,
          registeredLocationId,
          allLocationsPermitted,
          permittedLocationIds: allLocationsPermitted ? [] : permittedLocationIds
        },
        key.current()
      ),
    onSuccess: () => {
      key.next();
      onSuccess();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    enroll.mutate();
  };

  const togglePermittedLocation = (id: string) => {
    setPermittedLocationIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  return (
    <form className="customers-form customers-subcard" onSubmit={submit} aria-labelledby="customers-enroll-title">
      <h3 id="customers-enroll-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
          <circle cx="8.5" cy="7" r="4" />
          <line x1="20" y1="8" x2="20" y2="14" />
          <line x1="23" y1="11" x2="17" y2="11" />
        </svg>
        <span>{t("customers.loyalty.enroll.title").text}</span>
      </h3>
      <div className="customers-form-row customers-form-row--2col">
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <line x1="7" y1="8" x2="17" y2="8" />
              <line x1="7" y1="12" x2="17" y2="12" />
            </svg>
            <span>{t("customers.loyalty.regional_member_no").text}</span>
          </span>
          <input
            maxLength={60}
            aria-label={t("customers.loyalty.regional_member_no").text}
            value={regionalMemberNo}
            onChange={(e) => setRegionalMemberNo(e.target.value)}
          />
        </label>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6" />
            </svg>
            <span>{t("customers.loyalty.region_code").text}</span>
          </span>
          <input
            required
            maxLength={32}
            aria-label={t("customers.loyalty.region_code").text}
            value={regionCode}
            onChange={(e) => setRegionCode(e.target.value)}
          />
        </label>
      </div>
      <div className="customers-form-row customers-form-row--2col">
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="enroll-loyalty-tier">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
            <span>{t("customers.loyalty.tier").text}</span>
          </label>
          <CustomSelect
            id="enroll-loyalty-tier"
            ariaLabel={t("customers.loyalty.tier").text}
            value={loyaltyTier}
            onChange={(val) => setLoyaltyTier(val as (typeof TIERS)[number])}
            options={TIERS.map((tier) => ({
              value: tier,
              label: t(`customers.loyalty.tier.${tier}`).text
            }))}
          />
        </div>
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="enroll-registered-location">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            <span>{t("customers.loyalty.registered_location").text}</span>
          </label>
          <CustomSelect
            id="enroll-registered-location"
            ariaLabel={t("customers.loyalty.registered_location").text}
            required
            value={registeredLocationId}
            onChange={(val) => setRegisteredLocationId(val)}
            options={locations.map((loc) => ({
              value: loc.locationId,
              label: locationLabel(loc)
            }))}
          />
        </div>
      </div>
      <div className="customers-consents-card">
        <label className="customers-check">
          <input
            type="checkbox"
            checked={allLocationsPermitted}
            onChange={(e) => setAllLocationsPermitted(e.target.checked)}
          />
          <span>{t("customers.loyalty.all_locations_permitted").text}</span>
        </label>
        {!allLocationsPermitted && (
          <fieldset className="customers-field" style={{ marginTop: "var(--space-1)" }}>
            <legend>{t("customers.loyalty.permitted_locations").text}</legend>
            <div className="customers-consents-grid">
              {locations.map((loc) => (
                <label key={loc.locationId} className="customers-check">
                  <input
                    type="checkbox"
                    checked={permittedLocationIds.includes(loc.locationId)}
                    onChange={() => togglePermittedLocation(loc.locationId)}
                  />
                  <span>{locationLabel(loc)}</span>
                </label>
              ))}
            </div>
          </fieldset>
        )}
      </div>
      <div className="customers-form-actions">
        <button
          type="submit"
          className="customers-btn-primary"
          disabled={enroll.isPending || !regionCode.trim() || !registeredLocationId}
        >
          <span>{t("customers.loyalty.enroll.submit").text}</span>
        </button>
      </div>
      {enroll.isError && <p role="alert">{errorText(enroll.error, t("customers.error.generic").text)}</p>}
    </form>
  );
}

function UpdateLoyaltyForm({
  loyalty,
  locations,
  onSuccess
}: {
  loyalty: LoyaltyCustomerResponse;
  locations: Location[];
  onSuccess: () => void;
}) {
  const t = useT();
  const api = useCustomersApi();
  const key = useIdempotencyKey();
  const { locale } = useIntl();
  const locationLabel = (loc: Location) => inLocale(locale, loc.nameEn, loc.nameSi, loc.nameTa) || loc.locationCode;

  const [regionalMemberNo, setRegionalMemberNo] = useState(loyalty.regionalMemberNo ?? "");
  const [regionCode, setRegionCode] = useState(loyalty.regionCode);
  const [loyaltyTier, setLoyaltyTier] = useState<(typeof TIERS)[number]>(
    TIERS.includes(loyalty.loyaltyTier as (typeof TIERS)[number])
      ? (loyalty.loyaltyTier as (typeof TIERS)[number])
      : "STANDARD"
  );
  const [status, setStatus] = useState<(typeof LOYALTY_STATUSES)[number]>(
    LOYALTY_STATUSES.includes(loyalty.status as (typeof LOYALTY_STATUSES)[number])
      ? (loyalty.status as (typeof LOYALTY_STATUSES)[number])
      : "ACTIVE"
  );
  const [registeredLocationId, setRegisteredLocationId] = useState(loyalty.registeredLocationId);
  const [allLocationsPermitted, setAllLocationsPermitted] = useState(loyalty.allLocationsPermitted);
  const [permittedLocationIds, setPermittedLocationIds] = useState<string[]>(loyalty.permittedLocationIds ?? []);

  const update = useMutation({
    mutationFn: () =>
      api.updateRegionalRegistration(
        loyalty.customerId,
        {
          regionalMemberNo: regionalMemberNo.trim() || null,
          regionCode: regionCode.trim(),
          loyaltyTier,
          status,
          registeredLocationId,
          allLocationsPermitted,
          permittedLocationIds: allLocationsPermitted ? [] : permittedLocationIds
        },
        key.current()
      ),
    onSuccess: () => {
      key.next();
      onSuccess();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    update.mutate();
  };

  const togglePermittedLocation = (id: string) => {
    setPermittedLocationIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  return (
    <form className="customers-form customers-subcard" onSubmit={submit} aria-labelledby="customers-update-loyalty-title">
      <h3 id="customers-update-loyalty-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
          <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
        </svg>
        <span>{t("customers.loyalty.update.title").text}</span>
      </h3>
      <div className="customers-form-row customers-form-row--2col">
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <line x1="7" y1="8" x2="17" y2="8" />
              <line x1="7" y1="12" x2="17" y2="12" />
            </svg>
            <span>{t("customers.loyalty.regional_member_no").text}</span>
          </span>
          <input
            maxLength={60}
            aria-label={t("customers.loyalty.regional_member_no").text}
            value={regionalMemberNo}
            onChange={(e) => setRegionalMemberNo(e.target.value)}
          />
        </label>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6" />
            </svg>
            <span>{t("customers.loyalty.region_code").text}</span>
          </span>
          <input
            required
            maxLength={32}
            aria-label={t("customers.loyalty.region_code").text}
            value={regionCode}
            onChange={(e) => setRegionCode(e.target.value)}
          />
        </label>
      </div>
      <div className="customers-form-row customers-form-row--3col">
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="update-loyalty-tier">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
            <span>{t("customers.loyalty.tier").text}</span>
          </label>
          <CustomSelect
            id="update-loyalty-tier"
            ariaLabel={t("customers.loyalty.tier").text}
            value={loyaltyTier}
            onChange={(val) => setLoyaltyTier(val as (typeof TIERS)[number])}
            options={TIERS.map((tier) => ({
              value: tier,
              label: t(`customers.loyalty.tier.${tier}`).text
            }))}
          />
        </div>
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="update-loyalty-status">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <polyline points="12 6 12 12 16 14" />
            </svg>
            <span>{t("customers.loyalty.status").text}</span>
          </label>
          <CustomSelect
            id="update-loyalty-status"
            ariaLabel={t("customers.loyalty.status").text}
            value={status}
            onChange={(val) => setStatus(val as (typeof LOYALTY_STATUSES)[number])}
            options={LOYALTY_STATUSES.map((st) => ({
              value: st,
              label: t(`customers.loyalty.status.${st}`).text
            }))}
          />
        </div>
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="update-registered-location">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            <span>{t("customers.loyalty.registered_location").text}</span>
          </label>
          <CustomSelect
            id="update-registered-location"
            ariaLabel={t("customers.loyalty.registered_location").text}
            required
            value={registeredLocationId}
            onChange={(val) => setRegisteredLocationId(val)}
            options={locations.map((loc) => ({
              value: loc.locationId,
              label: locationLabel(loc)
            }))}
          />
        </div>
      </div>
      <div className="customers-consents-card">
        <label className="customers-check">
          <input
            type="checkbox"
            checked={allLocationsPermitted}
            onChange={(e) => setAllLocationsPermitted(e.target.checked)}
          />
          <span>{t("customers.loyalty.all_locations_permitted").text}</span>
        </label>
        {!allLocationsPermitted && (
          <fieldset className="customers-field" style={{ marginTop: "var(--space-1)" }}>
            <legend>{t("customers.loyalty.permitted_locations").text}</legend>
            <div className="customers-consents-grid">
              {locations.map((loc) => (
                <label key={loc.locationId} className="customers-check">
                  <input
                    type="checkbox"
                    checked={permittedLocationIds.includes(loc.locationId)}
                    onChange={() => togglePermittedLocation(loc.locationId)}
                  />
                  <span>{locationLabel(loc)}</span>
                </label>
              ))}
            </div>
          </fieldset>
        )}
      </div>
      <div className="customers-form-actions">
        <button
          type="submit"
          className="customers-btn-primary"
          disabled={update.isPending || !regionCode.trim() || !registeredLocationId}
        >
          <span>{t("customers.loyalty.update.submit").text}</span>
        </button>
      </div>
      {update.isError && <p role="alert">{errorText(update.error, t("customers.error.generic").text)}</p>}
    </form>
  );
}

function LoyaltyEligibilityChecker({
  customer,
  loyalty,
  locations
}: {
  customer: CustomerCard;
  loyalty: LoyaltyCustomerResponse;
  locations: Location[];
}) {
  const t = useT();
  const api = useCustomersApi();
  const { locale } = useIntl();
  const locationLabel = (loc: Location) => inLocale(locale, loc.nameEn, loc.nameSi, loc.nameTa) || loc.locationCode;

  const defaultIdentifier = loyalty.regionalMemberNo || customer.phone || customer.customerId;
  const [identifier, setIdentifier] = useState(defaultIdentifier);
  const [locationId, setLocationId] = useState(locations[0]?.locationId ?? "");
  const [result, setResult] = useState<LoyaltyEligibilityResponse | null>(null);

  const check = useMutation({
    mutationFn: () => api.checkLoyaltyEligibility(identifier.trim(), locationId || undefined),
    onSuccess: (data) => setResult(data)
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    check.mutate();
  };

  return (
    <form className="customers-form customers-subcard" onSubmit={submit} aria-labelledby="customers-eligibility-title">
      <h3 id="customers-eligibility-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        </svg>
        <span>{t("customers.loyalty.eligibility.title").text}</span>
      </h3>
      <div className="customers-form-row customers-form-row--2col">
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="11" cy="11" r="8" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
            <span>{t("customers.loyalty.eligibility.identifier").text}</span>
          </span>
          <input
            required
            maxLength={100}
            aria-label={t("customers.loyalty.eligibility.identifier").text}
            value={identifier}
            onChange={(e) => setIdentifier(e.target.value)}
          />
        </label>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            <span>{t("customers.loyalty.eligibility.branch").text}</span>
          </span>
          <CustomSelect
            ariaLabel={t("customers.loyalty.eligibility.branch").text}
            value={locationId}
            onChange={(val) => setLocationId(val)}
            options={[
              { value: "", label: t("customers.loyalty.location_choose").text },
              ...locations.map((loc) => ({
                value: loc.locationId,
                label: locationLabel(loc)
              }))
            ]}
          />
        </label>
      </div>
      <div className="customers-form-actions">
        <button type="submit" className="customers-btn-primary" disabled={check.isPending || !identifier.trim()}>
          <span>{t("customers.loyalty.eligibility.check").text}</span>
        </button>
      </div>
      {check.isError && <p role="alert">{errorText(check.error, t("customers.error.generic").text)}</p>}
      {result && (
        <dl className="customers-facts customers-subsection" role="region" aria-label={t("customers.loyalty.eligibility.title").text}>
          <dt>{t("customers.column.status").text}</dt>
          <dd>
            <StateChip
              state={result.eligible ? "issued" : "void"}
              label={result.eligible ? t("customers.loyalty.eligibility.eligible").text : t("customers.loyalty.eligibility.ineligible").text}
            />
          </dd>
          <dt>{t("customers.loyalty.eligibility.reason").text}</dt>
          <dd>{result.reason}</dd>
          {result.memberNo && (
            <>
              <dt>{t("customers.loyalty.regional_member_no").text}</dt>
              <dd>{result.memberNo}</dd>
            </>
          )}
          {result.loyaltyTier && (
            <>
              <dt>{t("customers.loyalty.tier").text}</dt>
              <dd>
                <StateChip state="issued" label={t(`customers.loyalty.tier.${result.loyaltyTier}`).text} />
              </dd>
            </>
          )}
        </dl>
      )}
    </form>
  );
}
