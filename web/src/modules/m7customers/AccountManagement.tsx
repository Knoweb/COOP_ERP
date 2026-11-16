import { useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { useFormatInstant } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { CustomSelect } from "./CustomSelect";
import { useCustomersApi, type Account, type AccountAction } from "./customersApi";
import { MFA_REQUIRED, accountActions, errorText, limitsChange, problemCode, raisesLimit } from "./customersView";
import "./customers.css";

/**
 * What the society office does with a credit account beyond a repayment (27A section 8, "Khata
 * page"; doc 27 section 4.2): the limit, hard block and offline cap (a higher limit asks for a
 * fresh second factor), suspend, reinstate and close with a reason, the account's history, and
 * adjustments that one person asks for and another approves.
 */
export function AccountManagement({ account, customerId }: { account: Account; customerId: string }) {
  const canManage = useHasPermission("cus.account.manage");
  const canAdjust = useHasPermission("cus.account.adjust");
  const canApprove = useHasPermission("cus.account.adjust_approve");
  return (
    <>
      {canManage && account.status !== "CLOSED" && <LimitsForm account={account} customerId={customerId} />}
      {canManage && <StatusActions account={account} customerId={customerId} />}
      {(canAdjust || canApprove) && (
        <Adjustments account={account} customerId={customerId} canAdjust={canAdjust} canApprove={canApprove} />
      )}
      <History accountId={account.accountId} />
    </>
  );
}

function useRefresh(customerId: string, accountId: string) {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: ["customers", "card", customerId] });
    queryClient.invalidateQueries({ queryKey: ["customers", "history", accountId] });
    queryClient.invalidateQueries({ queryKey: ["customers", "adjustments", accountId] });
    queryClient.invalidateQueries({ queryKey: ["customers", "statement", accountId] });
  };
}

/** Limit, hard block and offline cap, with the reason the audit keeps. */
function LimitsForm({ account, customerId }: { account: Account; customerId: string }) {
  const t = useT();
  const api = useCustomersApi();
  const key = useIdempotencyKey();
  const refresh = useRefresh(customerId, account.accountId);
  const [creditLimit, setCreditLimit] = useState(String(account.creditLimit));
  const [hardBlock, setHardBlock] = useState(account.hardBlock);
  const [offlineCap, setOfflineCap] = useState(account.offlineCap == null ? "" : String(account.offlineCap));
  const [reason, setReason] = useState("");
  const [nic, setNic] = useState("");
  const change = limitsChange(account, { creditLimit, hardBlock, offlineCap });
  const amend = useMutation({
    mutationFn: () =>
      api.amendLimits(
        account.accountId,
        // The NIC travels only when typed: the server asks for it (m7.account.nic_required) when
        // the limit rises above the threshold and none is recorded yet (CR-27A-1).
        { ...change, reason: reason.trim(), ...(nic.trim() === "" ? {} : { nic: nic.trim() }) },
        key.current()
      ),
    onSuccess: () => {
      key.next();
      setReason("");
      setNic("");
      refresh();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    amend.mutate();
  };
  return (
    <form className="customers-form customers-subcard" onSubmit={submit} aria-labelledby="customers-limits-title">
      <h3 id="customers-limits-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <path d="M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8" />
          <path d="M12 18V6" />
        </svg>
        <span>{t("customers.limits.title").text}</span>
      </h3>
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
            value={creditLimit}
            onChange={(e) => setCreditLimit(e.target.value)}
          />
        </label>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
              <line x1="8" y1="21" x2="16" y2="21" />
              <line x1="12" y1="17" x2="12" y2="21" />
            </svg>
            <span>{t("customers.account.offline_cap").text}</span>
          </span>
          <input
            type="number"
            min="0"
            step="0.01"
            aria-label={t("customers.account.offline_cap").text}
            value={offlineCap}
            onChange={(e) => setOfflineCap(e.target.value)}
          />
        </label>
      </div>
      <label className="customers-check">
        <input type="checkbox" checked={hardBlock} onChange={(e) => setHardBlock(e.target.checked)} />
        <span>{t("customers.limits.hard_block").text}</span>
      </label>
      <label className="customers-field">
        <span className="customers-field-label">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <line x1="8" y1="6" x2="21" y2="6" />
            <line x1="8" y1="12" x2="21" y2="12" />
            <line x1="8" y1="18" x2="21" y2="18" />
          </svg>
          <span>{t("customers.reason").text}</span>
        </span>
        <input
          maxLength={200}
          required
          aria-label={t("customers.reason").text}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      {raisesLimit(account, change) && (
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <line x1="15" y1="8" x2="17" y2="8" />
              <line x1="7" y1="16" x2="17" y2="16" />
            </svg>
            <span>{t("customers.limits.nic").text}</span>
          </span>
          <input
            maxLength={20}
            autoComplete="off"
            aria-label={t("customers.limits.nic").text}
            value={nic}
            onChange={(e) => setNic(e.target.value)}
          />
        </label>
      )}
      {raisesLimit(account, change) && <p role="note" className="customers-hint-text">{t("customers.limits.step_up").text}</p>}
      <div className="customers-form-actions">
        <button type="submit" className="customers-btn-primary" disabled={amend.isPending || change === null || reason.trim() === ""}>
          <span>{t("customers.limits.submit").text}</span>
        </button>
      </div>
      {amend.isError && (
        <p role="alert">
          {problemCode(amend.error) === MFA_REQUIRED
            ? t("customers.limits.step_up_needed").text
            : errorText(amend.error, t("customers.error.generic").text)}
        </p>
      )}
    </form>
  );
}

/** Suspend, reinstate or close, each with a reason; the choices follow the account's state. */
function StatusActions({ account, customerId }: { account: Account; customerId: string }) {
  const t = useT();
  const api = useCustomersApi();
  const key = useIdempotencyKey();
  const refresh = useRefresh(customerId, account.accountId);
  const actions = accountActions(account.status);
  const [action, setAction] = useState<AccountAction | "">("");
  const [reason, setReason] = useState("");
  const change = useMutation({
    mutationFn: () => api.changeStatus(account.accountId, action as AccountAction, reason.trim(), key.current()),
    onSuccess: () => {
      key.next();
      setAction("");
      setReason("");
      refresh();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  if (actions.length === 0) {
    return null;
  }
  const submit = (event: FormEvent) => {
    event.preventDefault();
    change.mutate();
  };
  return (
    <form className="customers-form customers-subcard" onSubmit={submit} aria-labelledby="customers-status-title">
      <h3 id="customers-status-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
        <span>{t("customers.status_action.title").text}</span>
      </h3>
      <div className="customers-form-row customers-form-row--2col">
        <div className="customers-field">
          <label className="customers-field-label" htmlFor="account-status-action">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polyline points="9 11 12 14 22 4" />
              <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
            </svg>
            <span>{t("customers.status_action.action").text}</span>
          </label>
          <CustomSelect
            id="account-status-action"
            ariaLabel={t("customers.status_action.action").text}
            required
            value={action}
            onChange={(val) => setAction(val as AccountAction | "")}
            options={[
              { value: "", label: t("customers.status_action.choose").text },
              ...actions.map((each) => ({
                value: each,
                label: t(`customers.status_action.${each}`).text
              }))
            ]}
          />
        </div>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <line x1="8" y1="6" x2="21" y2="6" />
              <line x1="8" y1="12" x2="21" y2="12" />
              <line x1="8" y1="18" x2="21" y2="18" />
            </svg>
            <span>{t("customers.reason").text}</span>
          </span>
          <input
            maxLength={200}
            required
            aria-label={t("customers.reason").text}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
      </div>
      <div className="customers-form-actions">
        <button type="submit" className="customers-btn-primary" disabled={change.isPending || action === "" || reason.trim() === ""}>
          <span>{t("customers.status_action.submit").text}</span>
        </button>
      </div>
      {change.isError && <p role="alert">{errorText(change.error, t("customers.error.generic").text)}</p>}
    </form>
  );
}

/** Adjustments: asked for with a reason, posted when another person approves. */
function Adjustments({
  account,
  customerId,
  canAdjust,
  canApprove
}: {
  account: Account;
  customerId: string;
  canAdjust: boolean;
  canApprove: boolean;
}) {
  const t = useT();
  const api = useCustomersApi();
  const formatInstant = useFormatInstant();
  const key = useIdempotencyKey();
  const approveKey = useIdempotencyKey();
  const refresh = useRefresh(customerId, account.accountId);
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const list = useQuery({
    queryKey: ["customers", "adjustments", account.accountId],
    queryFn: () => api.adjustments(account.accountId)
  });
  const ask = useMutation({
    mutationFn: () => api.requestAdjustment(account.accountId, { amount: Number(amount), reason: reason.trim() }, key.current()),
    onSuccess: () => {
      key.next();
      setAmount("");
      setReason("");
      refresh();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  const approve = useMutation({
    mutationFn: (adjustmentId: string) => api.approveAdjustment(account.accountId, adjustmentId, approveKey.current()),
    onSuccess: () => {
      approveKey.next();
      refresh();
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        approveKey.next();
      }
    }
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    ask.mutate();
  };
  return (
    <section className="customers-subcard" aria-labelledby="customers-adjustments-title">
      <h3 id="customers-adjustments-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="16" />
          <line x1="8" y1="12" x2="16" y2="12" />
        </svg>
        <span>{t("customers.adjustment.title").text}</span>
      </h3>
      {list.data && list.data.length > 0 && (
        <ul className="customers-list customer-phone-history-list" style={{ marginBottom: "var(--space-2)" }}>
          {list.data.map((adjustment) => (
            <li key={adjustment.adjustmentId} className="customer-phone-history-item">
              <span>
                <MoneyDisplay amount={adjustment.amount} /> · {adjustment.reason} · {formatInstant(adjustment.requestedAt)}
              </span>
              <div style={{ display: "inline-flex", alignItems: "center", gap: "var(--space-1)" }}>
                <StateChip
                  state={adjustment.status === "APPROVED" ? "issued" : "draft"}
                  label={t(`customers.adjustment.status.${adjustment.status}`).text}
                />
                {canApprove && adjustment.status === "REQUESTED" && (
                  <button
                    type="button"
                    className="customers-btn-primary"
                    disabled={approve.isPending}
                    onClick={() => approve.mutate(adjustment.adjustmentId)}
                  >
                    <span>{t("customers.adjustment.approve").text}</span>
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {approve.isError && <p role="alert">{errorText(approve.error, t("customers.error.generic").text)}</p>}
      {canAdjust && account.status !== "CLOSED" && (
        <form className="customers-form" onSubmit={submit}>
          <p className="customers-hint-text">{t("customers.adjustment.explain").text}</p>
          <div className="customers-form-row customers-form-row--2col">
            <label className="customers-field">
              <span className="customers-field-label">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <circle cx="12" cy="12" r="10" />
                  <path d="M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8" />
                  <path d="M12 18V6" />
                </svg>
                <span>{t("customers.adjustment.amount").text}</span>
              </span>
              <input
                type="number"
                step="0.01"
                required
                aria-label={t("customers.adjustment.amount").text}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </label>
            <label className="customers-field">
              <span className="customers-field-label">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <line x1="8" y1="6" x2="21" y2="6" />
                  <line x1="8" y1="12" x2="21" y2="12" />
                  <line x1="8" y1="18" x2="21" y2="18" />
                </svg>
                <span>{t("customers.reason").text}</span>
              </span>
              <input
                maxLength={200}
                required
                aria-label={t("customers.reason").text}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>
          </div>
          <div className="customers-form-actions">
            <button type="submit" className="customers-btn-primary" disabled={ask.isPending || !(Number(amount) !== 0) || reason.trim() === ""}>
              <span>{t("customers.adjustment.request").text}</span>
            </button>
          </div>
          {ask.isError && <p role="alert">{errorText(ask.error, t("customers.error.generic").text)}</p>}
        </form>
      )}
    </section>
  );
}

/** What changed on the account, newest first. */
function History({ accountId }: { accountId: string }) {
  const t = useT();
  const api = useCustomersApi();
  const formatInstant = useFormatInstant();
  const history = useQuery({ queryKey: ["customers", "history", accountId], queryFn: () => api.history(accountId) });
  if (!history.data || history.data.length === 0) {
    return null;
  }
  return (
    <section className="customers-subcard" aria-labelledby="customers-history-title">
      <h3 id="customers-history-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
        <span>{t("customers.history.title").text}</span>
      </h3>
      <ul className="customers-list customer-phone-history-list">
        {history.data.map((entry) => (
          <li key={entry.historyId} className="customer-phone-history-item">
            <span>{t(`customers.history.${entry.action}`).text} · {entry.reason}</span>
            <span className="customer-phone-dates">{formatInstant(entry.changedAt)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
