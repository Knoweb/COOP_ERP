import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { useCustomersApi } from "./customersApi";
import { MFA_REQUIRED, errorText, isReversible, problemCode, startOfMonthBefore } from "./customersView";
import { CustomersDatePicker } from "./CustomersDatePicker";
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
  const canReverse = useHasPermission("cus.payment.reverse");
  const [reversing, setReversing] = useState<string | null>(null);
  const [hoveredReverseDocId, setHoveredReverseDocId] = useState<string | null>(null);
  const statement = useQuery({
    queryKey: ["customers", "statement", accountId, from, to],
    queryFn: () => api.statement(accountId, from, to),
    enabled: from !== "" && to !== "" && from <= to
  });

  return (
    <main className="shell-page">
      <Link className="back-link back-pill-btn customers-back-link" to={`/customers/${customerId}`}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        <span>{t("customers.statement.back").text}</span>
      </Link>
      <PageHeader icon="statement" title={t("customers.statement.title").text} />

      <div className="customers-section-card statement-filter-card">
        <div className="statement-filter-grid">
          <CustomersDatePicker
            label={t("customers.statement.from").text}
            value={from}
            max={to}
            onChange={(val) => setFrom(val)}
          />
          <CustomersDatePicker
            label={t("customers.statement.to").text}
            value={to}
            min={from}
            onChange={(val) => setTo(val)}
          />
        </div>
      </div>

      {reversing && <ReverseForm documentId={reversing} accountId={accountId} onDone={() => setReversing(null)} />}

      {statement.isLoading && (
        <div className="customers-state-box">
          <p>{t("customers.loading").text}</p>
        </div>
      )}

      {statement.isError && (
        <div className="customers-state-box customers-state-box--error" role="alert">
          <p>{errorText(statement.error, t("customers.error.generic").text)}</p>
        </div>
      )}

      {statement.data && (
        <>
          <div className="statement-summary-bar">
            <div className="statement-summary-pill">
              <span className="statement-summary-label">{t("customers.statement.opening").text}</span>
              <span className="statement-summary-value">
                <MoneyDisplay amount={statement.data.openingBalance} />
              </span>
            </div>
            <div className="statement-summary-pill statement-summary-pill--closing">
              <span className="statement-summary-label">{t("customers.statement.closing").text}</span>
              <span className="statement-summary-value">
                <MoneyDisplay amount={statement.data.closingBalance} />
              </span>
            </div>
          </div>

          {statement.data.lines.length === 0 ? (
            <div className="customers-state-box customers-state-box--empty">
              <p>{t("customers.statement.empty").text}</p>
            </div>
          ) : (
            <div className="statement-table-wrap">
              <div className="modern-table-scroll">
                <table className="customers-data-table statement-table">
                    <thead>
                      <tr>
                        <th>{t("customers.statement.date").text}</th>
                        <th>{t("customers.statement.kind").text}</th>
                        <th>{t("customers.statement.document").text}</th>
                        <th className="customers-th-right">{t("customers.statement.amount").text}</th>
                        <th className="customers-th-right">{t("customers.statement.settled").text}</th>
                        <th className="customers-th-right">{t("customers.statement.running").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {statement.data.lines.map((line) => (
                        <tr key={line.postingId}>
                          <td className="customers-code-cell">{formatDate(line.businessDate)}</td>
                          <td>
                            <span className={`statement-kind-badge statement-kind-badge--${line.kind.toLowerCase()}`}>
                              {t(`customers.statement.kind.${line.kind}`).text}
                            </span>
                            {line.limitBreached && (
                              <span className="statement-breach-badge">
                                {t("customers.statement.breached").text}
                              </span>
                            )}
                          </td>
                          <td>
                            <div className="statement-doc-cell">
                              <span className="customers-code-cell">{line.documentNumber}</span>
                              {line.reversed && (
                                <span className="statement-reversed-badge">
                                  {t("customers.statement.reversed").text}
                                </span>
                              )}
                              {canReverse && isReversible(line) && (
                                <button
                                  type="button"
                                  className="statement-btn-reverse"
                                  onClick={() => setReversing(line.documentId)}
                                  onMouseEnter={() => setHoveredReverseDocId(line.documentId)}
                                  onMouseLeave={() => setHoveredReverseDocId(null)}
                                  style={{
                                    color:
                                      hoveredReverseDocId === line.documentId
                                        ? "var(--color-text)"
                                        : "var(--color-accent)",
                                    borderWidth: "var(--border-width-strong)",
                                    borderStyle: "solid",
                                    borderColor: "var(--color-accent)"
                                  }}
                                >
                                  <span
                                    className="statement-btn-reverse-text"
                                    style={{
                                      color:
                                        hoveredReverseDocId === line.documentId
                                          ? "var(--color-text)"
                                          : "var(--color-accent)"
                                    }}
                                  >
                                    {t("customers.reverse.open").text}
                                  </span>
                                </button>
                              )}
                            </div>
                          </td>
                          <td className="customers-td-right">
                            <MoneyDisplay amount={line.amount} />
                          </td>
                          <td className="customers-td-right">
                            <MoneyDisplay amount={line.settled} />
                          </td>
                          <td className="customers-td-right statement-running-cell">
                            <MoneyDisplay amount={line.runningBalance} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
        </>
      )}
    </main>
  );
}

/**
 * Reverse a repayment recorded in error (27A section 6, ReverseCustomerPayment): a reason, a fresh
 * second factor, and a reversing receipt that opens the charges it settled again.
 */
function ReverseForm({ documentId, accountId, onDone }: { documentId: string; accountId: string; onDone: () => void }) {
  const t = useT();
  const api = useCustomersApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [reason, setReason] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onDone();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onDone]);

  const reverse = useMutation({
    mutationFn: () => api.reversePayment(documentId, reason.trim(), key.current()),
    onSuccess: () => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["customers", "statement", accountId] });
      queryClient.invalidateQueries({ queryKey: ["customers", "card"] });
      onDone();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    reverse.mutate();
  };
  const modalNode = (
    <div
      className="statement-reverse-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onDone();
        }
      }}
    >
      <form className="statement-reverse-dialog" role="dialog" aria-modal="true" aria-labelledby="customers-reverse-title" onSubmit={submit}>
        <h2 id="customers-reverse-title">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          {t("customers.reverse.title").text}
        </h2>
        <p>{t("customers.reverse.explain").text}</p>
        <label className="customers-field">
          {t("customers.reason").text}
          <input
            ref={inputRef}
            maxLength={200}
            required
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="statement-reverse-actions">
          <button type="submit" className="statement-btn-reverse-submit" disabled={reverse.isPending || reason.trim() === ""}>
            {t("customers.reverse.submit").text}
          </button>
          <button type="button" className="statement-btn-cancel" onClick={onDone}>
            {t("customers.reverse.cancel").text}
          </button>
        </div>
        {reverse.isError && (
          <div className="customers-state-box customers-state-box--error" role="alert">
            <p>
              {problemCode(reverse.error) === MFA_REQUIRED
                ? t("customers.limits.step_up_needed").text
                : errorText(reverse.error, t("customers.error.generic").text)}
            </p>
          </div>
        )}
      </form>
    </div>
  );

  return typeof document !== "undefined" ? createPortal(modalNode, document.body) : modalNode;
}
