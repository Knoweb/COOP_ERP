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
    <form className="customers-form customers-subsection" onSubmit={submit} aria-labelledby="customers-limits-title">
      <h3 id="customers-limits-title">{t("customers.limits.title").text}</h3>
      <label className="customers-field">
        {t("customers.account.limit").text}
        <input type="number" min="0" step="0.01" required value={creditLimit} onChange={(e) => setCreditLimit(e.target.value)} />
      </label>
      <label className="customers-check">
        <input type="checkbox" checked={hardBlock} onChange={(e) => setHardBlock(e.target.checked)} />
        {t("customers.limits.hard_block").text}
      </label>
      <label className="customers-field">
        {t("customers.account.offline_cap").text}
        <input type="number" min="0" step="0.01" value={offlineCap} onChange={(e) => setOfflineCap(e.target.value)} />
      </label>
      <label className="customers-field">
        {t("customers.reason").text}
        <input maxLength={200} required value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      {raisesLimit(account, change) && (
        <label className="customers-field">
          {t("customers.limits.nic").text}
          <input maxLength={20} autoComplete="off" value={nic} onChange={(e) => setNic(e.target.value)} />
        </label>
      )}
      {raisesLimit(account, change) && <p role="note">{t("customers.limits.step_up").text}</p>}
      <button type="submit" disabled={amend.isPending || change === null || reason.trim() === ""}>
        {t("customers.limits.submit").text}
      </button>
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
    <form className="customers-form customers-subsection" onSubmit={submit} aria-labelledby="customers-status-title">
      <h3 id="customers-status-title">{t("customers.status_action.title").text}</h3>
      <label className="customers-field">
        {t("customers.status_action.action").text}
        <select
          aria-label={t("customers.status_action.action").text}
          value={action}
          required
          onChange={(e) => setAction(e.target.value as AccountAction | "")}
        >
          <option value="">{t("customers.status_action.choose").text}</option>
          {actions.map((each) => (
            <option key={each} value={each}>
              {t(`customers.status_action.${each}`).text}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-field">
        {t("customers.reason").text}
        <input maxLength={200} required value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <button type="submit" disabled={change.isPending || action === "" || reason.trim() === ""}>
        {t("customers.status_action.submit").text}
      </button>
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
    <section className="customers-subsection" aria-labelledby="customers-adjustments-title">
      <h3 id="customers-adjustments-title">{t("customers.adjustment.title").text}</h3>
      {list.data && list.data.length > 0 && (
        <ul className="customers-list">
          {list.data.map((adjustment) => (
            <li key={adjustment.adjustmentId}>
              <MoneyDisplay amount={adjustment.amount} /> · {adjustment.reason} · {formatInstant(adjustment.requestedAt)}{" "}
              <StateChip
                state={adjustment.status === "APPROVED" ? "issued" : "draft"}
                label={t(`customers.adjustment.status.${adjustment.status}`).text}
              />
              {canApprove && adjustment.status === "REQUESTED" && (
                <button type="button" disabled={approve.isPending} onClick={() => approve.mutate(adjustment.adjustmentId)}>
                  {t("customers.adjustment.approve").text}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {approve.isError && <p role="alert">{errorText(approve.error, t("customers.error.generic").text)}</p>}
      {canAdjust && account.status !== "CLOSED" && (
        <form className="customers-form" onSubmit={submit}>
          <p>{t("customers.adjustment.explain").text}</p>
          <label className="customers-field">
            {t("customers.adjustment.amount").text}
            <input type="number" step="0.01" required value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <label className="customers-field">
            {t("customers.reason").text}
            <input maxLength={200} required value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <button type="submit" disabled={ask.isPending || !(Number(amount) !== 0) || reason.trim() === ""}>
            {t("customers.adjustment.request").text}
          </button>
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
    <section className="customers-subsection" aria-labelledby="customers-history-title">
      <h3 id="customers-history-title">{t("customers.history.title").text}</h3>
      <ul className="customers-list">
        {history.data.map((entry) => (
          <li key={entry.historyId}>
            {formatInstant(entry.changedAt)} · {t(`customers.history.${entry.action}`).text} · {entry.reason}
          </li>
        ))}
      </ul>
    </section>
  );
}
