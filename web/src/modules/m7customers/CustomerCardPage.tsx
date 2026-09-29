import { useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { LangFallbackTag } from "../../shell/i18n/LangFallbackTag";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { AccountManagement } from "./AccountManagement";
import { useCustomersApi, type Account, type CustomerPayment } from "./customersApi";
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
  const canRecordPrivacy = useHasPermission("cus.privacy.record");

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
      <h1>
        {name.text}
        {name.isFallback && <LangFallbackTag />}
      </h1>
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
