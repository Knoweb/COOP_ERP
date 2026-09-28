import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { useCustomersApi } from "./customersApi";
import { businessToday, errorText, startOfMonthBefore } from "./customersView";
import "./customers.css";

/**
 * The statement of an account (27A section 8, "Statement"): for a period of business dates, the
 * balance brought forward, every posting with what was settled of it and the running balance, and
 * the balance carried forward. Starts at the first of the month two months back, so the demo's
 * eight weeks show at once.
 */
export function StatementPage() {
  const { customerId = "", accountId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = useCustomersApi();
  const today = businessToday();
  const [from, setFrom] = useState(startOfMonthBefore(today, 2));
  const [to, setTo] = useState(today);
  const statement = useQuery({
    queryKey: ["customers", "statement", accountId, from, to],
    queryFn: () => api.statement(accountId, from, to),
    enabled: from !== "" && to !== "" && from <= to
  });

  return (
    <main className="shell-page">
      <Link className="back-link" to={`/customers/${customerId}`}>
        <svg viewBox="0 0 24 24">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        {t("customers.statement.back").text}
      </Link>
      <h1>{t("customers.statement.title").text}</h1>
      <div className="customers-filter-bar">
        <label className="customers-field">
          {t("customers.statement.from").text}
          <input type="date" max={to} value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="customers-field">
          {t("customers.statement.to").text}
          <input type="date" min={from} value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </div>
      {statement.isLoading && <p>{t("customers.loading").text}</p>}
      {statement.isError && <p role="alert">{errorText(statement.error, t("customers.error.generic").text)}</p>}
      {statement.data && (
        <>
          <p>
            {t("customers.statement.opening").text}: <MoneyDisplay amount={statement.data.openingBalance} />
          </p>
          {statement.data.lines.length === 0 ? (
            <p>{t("customers.statement.empty").text}</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>{t("customers.statement.date").text}</th>
                  <th>{t("customers.statement.kind").text}</th>
                  <th>{t("customers.statement.document").text}</th>
                  <th>{t("customers.statement.amount").text}</th>
                  <th>{t("customers.statement.settled").text}</th>
                  <th>{t("customers.statement.running").text}</th>
                </tr>
              </thead>
              <tbody>
                {statement.data.lines.map((line) => (
                  <tr key={line.postingId}>
                    <td>{formatDate(line.businessDate)}</td>
                    <td>
                      {t(`customers.statement.kind.${line.kind}`).text}
                      {line.limitBreached && <> · {t("customers.statement.breached").text}</>}
                    </td>
                    <td>{line.documentNumber}</td>
                    <td>
                      <MoneyDisplay amount={line.amount} />
                    </td>
                    <td>
                      <MoneyDisplay amount={line.settled} />
                    </td>
                    <td>
                      <MoneyDisplay amount={line.runningBalance} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p>
            {t("customers.statement.closing").text}: <MoneyDisplay amount={statement.data.closingBalance} size="total" />
          </p>
        </>
      )}
    </main>
  );
}
