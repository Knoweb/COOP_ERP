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
import { ConsentManagement } from "./ConsentManagement";
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
    <main className="shell-page">
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
      <dl className="customers-facts">
        <dt>{t("customers.column.phone").text}</dt>
        <dd>{customer.phone}</dd>
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
            .join(", ")}
        </dd>
        <dt>{t("customers.field.tags").text}</dt>
        <dd>{customer.tags.join(", ") || "—"}</dd>
        <dt>{t("customers.card.registered").text}</dt>
        <dd>{formatInstant(customer.registeredAt)}</dd>
      </dl>
      {customer.phones.length > 1 && (
        <section className="customers-section">
          <h2>{t("customers.card.phone_history").text}</h2>
          <ul className="customers-list">
            {customer.phones.map((phone) => (
              <li key={phone.validFrom}>
                {phone.phone} · {formatInstant(phone.validFrom)}
                {phone.validTo && <> – {formatInstant(phone.validTo)}</>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <ConsentManagement
        customer={customer}
        canManage={canManageCustomer}
      />

      <MembershipLoyaltySection
        customer={customer}
        loyalty={loyalty.data}
        locations={locations.data ?? []}
        isLoading={loyalty.isLoading}
        canRegister={canRegisterCustomer}
        canManage={canManageCustomer}
      />

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
    <Link className="back-link" to="/customers">
      <svg viewBox="0 0 24 24">
        <path d="M15 18l-6-6 6-6" />
        <path d="M9 12h10" />
      </svg>
      {t("customers.back").text}
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
    <section className="customers-section" aria-labelledby="customers-account-title">
      <h2 id="customers-account-title">
        {t("customers.account.title").text} {account.accountNo}{" "}
        <StateChip state={statusChip(account.status)} label={t(`customers.account.status.${account.status}`).text} />
      </h2>
      <dl className="customers-facts">
        <dt>{t("customers.account.limit").text}</dt>
        <dd>
          <MoneyDisplay amount={account.creditLimit} />
        </dd>
        <dt>{t("customers.account.balance").text}</dt>
        <dd>
          <MoneyDisplay amount={account.balance} size="total" />
        </dd>
        <dt>{t("customers.account.available").text}</dt>
        <dd>
          <MoneyDisplay amount={account.available} />
        </dd>
        <dt>{t("customers.account.offline_cap").text}</dt>
        <dd>{account.offlineCap != null && <MoneyDisplay amount={account.offlineCap} />}</dd>
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
        <p role="note" className="customers-warning">
          <StateChip state="alert" label={t("customers.account.near_limit", undefined, { percent: used }).text} />
        </p>
      )}
      <table>
        <caption>{t("customers.account.ageing").text}</caption>
        <thead>
          <tr>
            <th>{t("customers.ageing.0_30").text}</th>
            <th>{t("customers.ageing.31_60").text}</th>
            <th>{t("customers.ageing.61_90").text}</th>
            <th>{t("customers.ageing.over_90").text}</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              <MoneyDisplay amount={account.ageing.days0To30} />
            </td>
            <td>
              <MoneyDisplay amount={account.ageing.days31To60} />
            </td>
            <td>
              <MoneyDisplay amount={account.ageing.days61To90} />
            </td>
            <td>
              <MoneyDisplay amount={account.ageing.over90} />
            </td>
          </tr>
        </tbody>
      </table>
      <p>
        <Link to={`/customers/${customerId}/accounts/${account.accountId}/statement`}>{t("customers.statement.open").text}</Link>
      </p>
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
    <section className="customers-section">
      <h2>{t("customers.account.open.title").text}</h2>
      <form className="customers-form" onSubmit={submit}>
        <label className="customers-field">
          {t("customers.account.limit").text}
          <input type="number" min="0" step="0.01" required value={limit} onChange={(e) => setLimit(e.target.value)} />
        </label>
        <label className="customers-field">
          {t("customers.account.nic").text}
          <input maxLength={20} autoComplete="off" value={nic} onChange={(e) => setNic(e.target.value)} />
        </label>
        <button type="submit" disabled={open.isPending || limit === ""}>
          {t("customers.account.open.submit").text}
        </button>
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
    <form className="customers-form customers-subsection" onSubmit={submit} aria-labelledby="customers-repayment-title">
      <h3 id="customers-repayment-title">{t("customers.payment.title").text}</h3>
      <p>{t("customers.payment.oldest_first").text}</p>
      <label className="customers-field">
        {t("customers.payment.amount").text}
        <input type="number" min="0.01" step="0.01" required value={amount} onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label className="customers-field">
        {t("customers.payment.method").text}
        <select
          aria-label={t("customers.payment.method").text}
          value={method}
          onChange={(e) => setMethod(e.target.value as (typeof METHODS)[number])}
        >
          {METHODS.map((each) => (
            <option key={each} value={each}>
              {t(`customers.payment.method.${each}`).text}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-field">
        {t("customers.payment.reference").text}
        <input maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} />
      </label>
      <button type="submit" disabled={record.isPending || !(Number(amount) > 0)}>
        {t("customers.payment.record").text}
      </button>
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
    <section className="customers-section" aria-labelledby="customers-loyalty-title">
      <h2 id="customers-loyalty-title">{t("customers.loyalty.title").text}</h2>
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
    <form className="customers-form customers-subsection" onSubmit={submit} aria-labelledby="customers-enroll-title">
      <h3 id="customers-enroll-title">{t("customers.loyalty.enroll.title").text}</h3>
      <label className="customers-field">
        {t("customers.loyalty.regional_member_no").text}
        <input
          maxLength={60}
          value={regionalMemberNo}
          onChange={(e) => setRegionalMemberNo(e.target.value)}
        />
      </label>
      <label className="customers-field">
        {t("customers.loyalty.region_code").text}
        <input
          required
          maxLength={32}
          value={regionCode}
          onChange={(e) => setRegionCode(e.target.value)}
        />
      </label>
      <label className="customers-field">
        {t("customers.loyalty.tier").text}
        <select
          aria-label={t("customers.loyalty.tier").text}
          value={loyaltyTier}
          onChange={(e) => setLoyaltyTier(e.target.value as (typeof TIERS)[number])}
        >
          {TIERS.map((tier) => (
            <option key={tier} value={tier}>
              {t(`customers.loyalty.tier.${tier}`).text}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-field">
        {t("customers.loyalty.registered_location").text}
        <select
          aria-label={t("customers.loyalty.registered_location").text}
          required
          value={registeredLocationId}
          onChange={(e) => setRegisteredLocationId(e.target.value)}
        >
          {locations.map((loc) => (
            <option key={loc.locationId} value={loc.locationId}>
              {locationLabel(loc)}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-check">
        <input
          type="checkbox"
          checked={allLocationsPermitted}
          onChange={(e) => setAllLocationsPermitted(e.target.checked)}
        />
        {t("customers.loyalty.all_locations_permitted").text}
      </label>
      {!allLocationsPermitted && (
        <fieldset className="customers-field">
          <legend>{t("customers.loyalty.permitted_locations").text}</legend>
          {locations.map((loc) => (
            <label key={loc.locationId} className="customers-check">
              <input
                type="checkbox"
                checked={permittedLocationIds.includes(loc.locationId)}
                onChange={() => togglePermittedLocation(loc.locationId)}
              />
              {locationLabel(loc)}
            </label>
          ))}
        </fieldset>
      )}
      <button
        type="submit"
        disabled={enroll.isPending || !regionCode.trim() || !registeredLocationId}
      >
        {t("customers.loyalty.enroll.submit").text}
      </button>
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
    <form className="customers-form customers-subsection" onSubmit={submit} aria-labelledby="customers-update-loyalty-title">
      <h3 id="customers-update-loyalty-title">{t("customers.loyalty.update.title").text}</h3>
      <label className="customers-field">
        {t("customers.loyalty.regional_member_no").text}
        <input
          maxLength={60}
          value={regionalMemberNo}
          onChange={(e) => setRegionalMemberNo(e.target.value)}
        />
      </label>
      <label className="customers-field">
        {t("customers.loyalty.region_code").text}
        <input
          required
          maxLength={32}
          value={regionCode}
          onChange={(e) => setRegionCode(e.target.value)}
        />
      </label>
      <label className="customers-field">
        {t("customers.loyalty.tier").text}
        <select
          aria-label={t("customers.loyalty.tier").text}
          value={loyaltyTier}
          onChange={(e) => setLoyaltyTier(e.target.value as (typeof TIERS)[number])}
        >
          {TIERS.map((tier) => (
            <option key={tier} value={tier}>
              {t(`customers.loyalty.tier.${tier}`).text}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-field">
        {t("customers.loyalty.status").text}
        <select
          aria-label={t("customers.loyalty.status").text}
          value={status}
          onChange={(e) => setStatus(e.target.value as (typeof LOYALTY_STATUSES)[number])}
        >
          {LOYALTY_STATUSES.map((st) => (
            <option key={st} value={st}>
              {t(`customers.loyalty.status.${st}`).text}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-field">
        {t("customers.loyalty.registered_location").text}
        <select
          aria-label={t("customers.loyalty.registered_location").text}
          required
          value={registeredLocationId}
          onChange={(e) => setRegisteredLocationId(e.target.value)}
        >
          {locations.map((loc) => (
            <option key={loc.locationId} value={loc.locationId}>
              {locationLabel(loc)}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-check">
        <input
          type="checkbox"
          checked={allLocationsPermitted}
          onChange={(e) => setAllLocationsPermitted(e.target.checked)}
        />
        {t("customers.loyalty.all_locations_permitted").text}
      </label>
      {!allLocationsPermitted && (
        <fieldset className="customers-field">
          <legend>{t("customers.loyalty.permitted_locations").text}</legend>
          {locations.map((loc) => (
            <label key={loc.locationId} className="customers-check">
              <input
                type="checkbox"
                checked={permittedLocationIds.includes(loc.locationId)}
                onChange={() => togglePermittedLocation(loc.locationId)}
              />
              {locationLabel(loc)}
            </label>
          ))}
        </fieldset>
      )}
      <button
        type="submit"
        disabled={update.isPending || !regionCode.trim() || !registeredLocationId}
      >
        {t("customers.loyalty.update.submit").text}
      </button>
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
    <form className="customers-form customers-subsection" onSubmit={submit} aria-labelledby="customers-eligibility-title">
      <h3 id="customers-eligibility-title">{t("customers.loyalty.eligibility.title").text}</h3>
      <label className="customers-field">
        {t("customers.loyalty.eligibility.identifier").text}
        <input
          required
          maxLength={100}
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
        />
      </label>
      <label className="customers-field">
        {t("customers.loyalty.eligibility.branch").text}
        <select
          aria-label={t("customers.loyalty.eligibility.branch").text}
          value={locationId}
          onChange={(e) => setLocationId(e.target.value)}
        >
          <option value="">{t("customers.loyalty.location_choose").text}</option>
          {locations.map((loc) => (
            <option key={loc.locationId} value={loc.locationId}>
              {locationLabel(loc)}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={check.isPending || !identifier.trim()}>
        {t("customers.loyalty.eligibility.check").text}
      </button>
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
