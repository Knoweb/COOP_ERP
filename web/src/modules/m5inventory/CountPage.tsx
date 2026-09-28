import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useSession } from "../../shell/auth/session";
import { ApprovalBar, type ApprovalAction } from "../../shell/components/ApprovalBar";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi, type Count } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { countChip, countLinesOf, lotKey, varianceOf, type CountEntry } from "./stockControl";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";

/**
 * One count (25A section 8): the stocktake sheet while it is being counted (the lots noted at the
 * start with the book, a counted quantity or a reason to skip for each, the variance shown once
 * a quantity is entered), then the adjustment voucher (expected, counted, variance and value per
 * line, the band, and Approve or Reject for another person than the counter), then the closed
 * count with a link to each item's stock card.
 */
export function CountPage() {
  const { taskId = "" } = useParams();
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const session = useSession();
  const formatDate = useFormatDate();
  const canRecord = useHasPermission("shop.count.record");
  const canApprove = useHasPermission("inv.adjust.approve");
  const startKey = useIdempotencyKey();
  const submitKey = useIdempotencyKey();
  const approveKey = useIdempotencyKey();
  const rejectKey = useIdempotencyKey();
  const [entries, setEntries] = useState<Record<string, CountEntry>>({});
  const [rejecting, setRejecting] = useState(false);

  const count = useQuery({ queryKey: ["inventory", "count", taskId], queryFn: () => api.count(taskId) });

  const done = (key: { next: () => void }) => () => {
    key.next();
    setRejecting(false);
    queryClient.invalidateQueries({ queryKey: ["inventory"] });
  };
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const start = useMutation({
    mutationFn: () => api.startCount(taskId, startKey.current()),
    onSuccess: done(startKey),
    onError: forget(startKey)
  });
  const submit = useMutation({
    mutationFn: (lines: NonNullable<ReturnType<typeof countLinesOf>>) =>
      api.submitCount(taskId, lines, submitKey.current()),
    onSuccess: done(submitKey),
    onError: forget(submitKey)
  });
  const approve = useMutation({
    mutationFn: () => api.approveAdjustment(taskId, approveKey.current()),
    onSuccess: done(approveKey),
    onError: forget(approveKey)
  });
  const reject = useMutation({
    mutationFn: (reason: string) => api.rejectAdjustment(taskId, reason, rejectKey.current()),
    onSuccess: done(rejectKey),
    onError: forget(rejectKey)
  });

  if (count.isLoading) {
    return <main className="shell-page">{t("inventory.loading").text}</main>;
  }
  if (count.isError || !count.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(count.error, t("inventory.error.generic").text)}</p>
      </main>
    );
  }

  const task: Count = count.data;
  const batchText = (batchNo: string | undefined) =>
    isSyntheticBatchNo(batchNo) ? t("inventory.field.batch_not_tracked").text : (batchNo ?? "");
  const lines = countLinesOf(task, entries);
  const entry = (key: string) => entries[key] ?? { counted: "", skip: "" };
  const setEntry = (key: string, change: Partial<CountEntry>) =>
    setEntries({ ...entries, [key]: { ...entry(key), ...change } });
  const ownCount = session?.userId !== undefined && session.userId === task.submittedBy;

  const approvals: ApprovalAction[] =
    task.status === "VARIANCE_REVIEW" && canApprove
      ? [
          {
            id: "approve",
            label: t("inventory.count.approve").text,
            primary: true,
            pending: approve.isPending,
            disabledReason: ownCount ? t("inventory.count.sod").text : undefined,
            onClick: () => approve.mutate()
          },
          {
            id: "reject",
            label: t("inventory.count.reject").text,
            pending: reject.isPending,
            onClick: () => setRejecting(true)
          }
        ]
      : [];

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory/counts">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.count.back").text}
      </Link>
      <h1>{t("inventory.count.sheet_title").text}</h1>
      <p>
        <StateChip state={countChip(task.status)} label={t(`inventory.count.status.${task.status}`).text} />
        {task.outcome && ` ${t(`inventory.count.outcome.${task.outcome}`).text}`}
      </p>
      <p>{`${t("inventory.count.scheduled_for").text}: ${formatDate(task.scheduledFor)} · ${t(`inventory.count.scope.${task.scopeKind}`).text}`}</p>

      {task.status === "SCHEDULED" && canRecord && (
        <div className="inventory-action-bar">
          <button type="button" disabled={start.isPending} onClick={() => start.mutate()}>
            {t("inventory.count.start").text}
          </button>
        </div>
      )}

      {task.status === "COUNTING" && (
        <section className="inventory-section">
          <p>{t("inventory.count.trading_note").text}</p>
          {task.expectation.length === 0 && <p>{t("inventory.count.empty_sheet").text}</p>}
          {task.expectation.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>{t("inventory.column.item").text}</th>
                  <th>{t("inventory.column.batch").text}</th>
                  <th>{t("inventory.column.condition").text}</th>
                  <th>{t("inventory.count.column.expected").text}</th>
                  <th>{t("inventory.count.column.counted").text}</th>
                  <th>{t("inventory.count.column.variance").text}</th>
                  <th>{t("inventory.count.column.skip").text}</th>
                </tr>
              </thead>
              <tbody>
                {task.expectation.map((lot) => {
                  const key = lotKey(lot.batchId, lot.condition);
                  const batch = batchText(lot.batchNo);
                  const variance = varianceOf(lot.expectedQty, entries[key]);
                  return (
                    <tr key={key}>
                      <td>
                        <SkuLabel skuId={lot.skuId} />
                      </td>
                      <td>{batch}</td>
                      <td>{t(`inventory.condition.${lot.condition}`).text}</td>
                      <td className="numeric-cell">{lot.expectedQty}</td>
                      <td>
                        <input
                          inputMode="decimal"
                          aria-label={t("inventory.count.counted_for", undefined, { batch: `${batch} ${t(`inventory.condition.${lot.condition}`).text}` }).text}
                          value={entry(key).counted}
                          disabled={!canRecord}
                          onChange={(event) => setEntry(key, { counted: event.target.value })}
                        />
                      </td>
                      <td className="numeric-cell">{variance === null ? "" : variance > 0 ? `+${variance}` : variance}</td>
                      <td>
                        <input
                          aria-label={t("inventory.count.skip_for", undefined, { batch: `${batch} ${t(`inventory.condition.${lot.condition}`).text}` }).text}
                          value={entry(key).skip}
                          disabled={!canRecord}
                          onChange={(event) => setEntry(key, { skip: event.target.value })}
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {canRecord && (
            <div className="inventory-action-bar">
              <button type="button" disabled={lines === null || submit.isPending} onClick={() => lines && submit.mutate(lines)}>
                {t("inventory.count.submit").text}
              </button>
              {lines === null && <span>{t("inventory.count.submit_hint").text}</span>}
            </div>
          )}
        </section>
      )}

      {task.lines.length > 0 && (
        <section className="inventory-section">
          <table>
            <thead>
              <tr>
                <th>{t("inventory.column.item").text}</th>
                <th>{t("inventory.column.batch").text}</th>
                <th>{t("inventory.count.column.expected").text}</th>
                <th>{t("inventory.count.column.counted").text}</th>
                <th>{t("inventory.count.column.variance").text}</th>
                <th>{t("inventory.count.column.value").text}</th>
                <th>{t("inventory.count.column.tolerance").text}</th>
              </tr>
            </thead>
            <tbody>
              {task.lines.map((line) => (
                <tr key={line.lineNo}>
                  <td>
                    <Link to={`/inventory/locations/${task.locationId}/skus/${line.skuId}`}>
                      <SkuLabel skuId={line.skuId} />
                    </Link>
                  </td>
                  <td>{batchText(line.batchNo)}</td>
                  <td className="numeric-cell">{line.expectedQty}</td>
                  <td className="numeric-cell">{line.countedQty ?? t("inventory.count.skipped").text}</td>
                  <td className="numeric-cell">{line.varianceQty > 0 ? `+${line.varianceQty}` : line.varianceQty}</td>
                  <td className="numeric-cell">
                    <MoneyDisplay amount={line.varianceValue} />
                  </td>
                  <td>
                    {line.varianceQty === 0
                      ? ""
                      : line.withinTolerance
                        ? t("inventory.count.within").text
                        : t("inventory.count.beyond").text}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {task.reviewValue != null && (
            <p>
              {`${t("inventory.count.review_value").text}: `}
              <MoneyDisplay amount={task.reviewValue} />
              {task.reviewBand != null && ` · ${t("inventory.count.band", undefined, { band: task.reviewBand }).text}`}
            </p>
          )}
          {task.reviewReason && <p>{task.reviewReason}</p>}
        </section>
      )}

      <ApprovalBar actions={approvals} />
      {rejecting && (
        <ReasonCapture
          title={t("inventory.count.reject.question").text}
          codes={["RECOUNT", "WRONG_ITEM", "OTHER"].map((code) => ({
            code,
            label: t(`inventory.count.reason.${code}`).text
          }))}
          pending={reject.isPending}
          onCancel={() => setRejecting(false)}
          onConfirm={(code, text) =>
            reject.mutate([t(`inventory.count.reason.${code}`).text, text].filter(Boolean).join(": "))
          }
        />
      )}
      {[start, submit, approve, reject].map((m, i) =>
        m.isError ? (
          <p key={i} role="alert">
            {errorText(m.error, t("inventory.error.generic").text)}
          </p>
        ) : null
      )}
    </main>
  );
}
