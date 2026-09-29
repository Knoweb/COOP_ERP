import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { useIntegrationApi, type JournalExport } from "./integrationApi";
import { defaultExportPeriod, errorText, journalFileName } from "./integrationView";
import "./integration.css";

/**
 * Journal exports (29A section 8, demo scope): the period form with what waits for the next
 * export, "Generate", and the run history with the totals, the file for the accounting package
 * and the reconciliation of each export. Every amount goes through MoneyDisplay; the page adds
 * nothing up itself, the server's reconciliation does.
 */
export function JournalExportsPage() {
  const t = useT();
  const api = useIntegrationApi();
  const queryClient = useQueryClient();
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const canExport = useHasPermission("int.journal.export");
  const exportKey = useIdempotencyKey();

  const [period, setPeriod] = useState(() => defaultExportPeriod(new Date()));
  const [message, setMessage] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState<string | null>(null);

  const periodValid = period.from !== "" && period.to !== "" && period.from <= period.to;
  const pending = useQuery({
    queryKey: ["integration", "pending", period.from, period.to],
    queryFn: () => api.pending(period.from, period.to),
    enabled: periodValid
  });
  const exports = useQuery({ queryKey: ["integration", "exports"], queryFn: () => api.exports() });

  async function generate() {
    setMessage(null);
    setBusy(true);
    try {
      const created = await api.requestExport(period.from, period.to, exportKey.current());
      exportKey.next();
      setMessage({
        kind: "status",
        text: t("integration.journal.generated", undefined, { lines: String(created.lineCount) }).text
      });
      setOpen(created.exportId);
      await queryClient.invalidateQueries({ queryKey: ["integration"] });
    } catch (error) {
      setMessage({ kind: "alert", text: errorText(error, t("integration.error.generic").text) });
    } finally {
      setBusy(false);
    }
  }

  async function download(journal: JournalExport) {
    setMessage(null);
    try {
      const text = await api.file(journal.exportId);
      const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = journalFileName(journal.periodFrom, journal.periodTo);
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      setMessage({ kind: "alert", text: errorText(error, t("integration.error.generic").text) });
    }
  }

  return (
    <main className="shell-page">
      <h1>{t("integration.journal.title").text}</h1>
      <p>{t("integration.journal.intro").text}</p>

      <form
        className="modern-filter-panel integration-bar"
        onSubmit={(event) => {
          event.preventDefault();
          void generate();
        }}
      >
        <label className="modern-field">
          <span className="modern-field__label integration-label">{t("integration.field.from").text}</span>
          <input
            type="date"
            value={period.from}
            required
            onChange={(event) => setPeriod({ ...period, from: event.target.value })}
          />
        </label>
        <label className="modern-field">
          <span className="modern-field__label integration-label">{t("integration.field.to").text}</span>
          <input
            type="date"
            value={period.to}
            required
            onChange={(event) => setPeriod({ ...period, to: event.target.value })}
          />
        </label>
        {canExport && (
          <button
            type="submit"
            className="modern-btn modern-btn--primary"
            disabled={busy || !periodValid || pending.data?.postings === 0}
          >
            {busy ? t("integration.journal.generating").text : t("integration.journal.generate").text}
          </button>
        )}
      </form>

      {pending.data && (
        <p role="status" aria-live="polite">
          {pending.data.postings === 0 ? (
            t("integration.journal.pending.none").text
          ) : (
            <>
              {t("integration.journal.pending", undefined, { count: String(pending.data.postings) }).text}{" "}
              <MoneyDisplay amount={String(pending.data.amount)} />
            </>
          )}
        </p>
      )}
      {message && <p role={message.kind}>{message.text}</p>}

      <section>
        <h2>{t("integration.journal.history").text}</h2>
        {exports.isLoading && <p>{t("integration.loading").text}</p>}
        {exports.isError && <p role="alert">{errorText(exports.error, t("integration.error.generic").text)}</p>}
        {exports.data && exports.data.length === 0 && <p>{t("integration.journal.history.empty").text}</p>}
        {exports.data && exports.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th scope="col">{t("integration.journal.col.period").text}</th>
                    <th scope="col">{t("integration.journal.col.generated").text}</th>
                    <th scope="col">{t("integration.journal.col.status").text}</th>
                    <th scope="col" className="integration-number">{t("integration.journal.col.lines").text}</th>
                    <th scope="col" className="integration-number">{t("integration.journal.col.debit").text}</th>
                    <th scope="col" className="integration-number">{t("integration.journal.col.credit").text}</th>
                    <th scope="col">{t("integration.journal.col.actions").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {exports.data.map((journal) => (
                    <tr key={journal.exportId}>
                      <td>
                        {formatDate(journal.periodFrom)} – {formatDate(journal.periodTo)}
                      </td>
                      <td>{formatInstant(journal.generatedAt)}</td>
                      <td>
                        <StateChip state="issued" label={t(`integration.journal.status.${journal.status}`).text} />
                      </td>
                      <td className="integration-number">{journal.lineCount}</td>
                      <td className="integration-number">
                        <MoneyDisplay amount={String(journal.totalDebit)} />
                      </td>
                      <td className="integration-number">
                        <MoneyDisplay amount={String(journal.totalCredit)} />
                      </td>
                      <td className="integration-actions">
                        {canExport && (
                          <button
                            type="button"
                            className="modern-btn"
                            aria-label={t("integration.journal.download.label", undefined, {
                              from: journal.periodFrom,
                              to: journal.periodTo
                            }).text}
                            onClick={() => void download(journal)}
                          >
                            {t("integration.journal.download").text}
                          </button>
                        )}
                        <button
                          type="button"
                          className="modern-btn"
                          aria-expanded={open === journal.exportId}
                          onClick={() => setOpen(open === journal.exportId ? null : journal.exportId)}
                        >
                          {t("integration.journal.reconcile").text}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      {open && <ReconciliationPanel exportId={open} />}
    </main>
  );
}

/** The server's reconciliation of one export: balanced or not, the check against the record, by role. */
function ReconciliationPanel({ exportId }: { exportId: string }) {
  const t = useT();
  const api = useIntegrationApi();
  const reconciliation = useQuery({
    queryKey: ["integration", "reconciliation", exportId],
    queryFn: () => api.reconciliation(exportId)
  });

  if (reconciliation.isLoading) {
    return <p>{t("integration.loading").text}</p>;
  }
  if (reconciliation.isError || !reconciliation.data) {
    return <p role="alert">{errorText(reconciliation.error, t("integration.error.generic").text)}</p>;
  }
  const r = reconciliation.data;
  const sound = r.balanced && r.matchesRecordedTotals && r.matchesRecordedHash;
  return (
    <section aria-labelledby="integration-reconciliation">
      <h2 id="integration-reconciliation">{t("integration.reconciliation.title").text}</h2>
      <p role="status">
        <StateChip
          state={sound ? "issued" : "alert"}
          label={sound ? t("integration.reconciliation.balanced").text : t("integration.reconciliation.unbalanced").text}
        />{" "}
        {r.matchesRecordedHash
          ? t("integration.reconciliation.hash.ok").text
          : t("integration.reconciliation.hash.changed").text}
      </p>
      <div className="modern-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table">
            <thead>
              <tr>
                <th scope="col">{t("integration.reconciliation.col.role").text}</th>
                <th scope="col" className="integration-number">{t("integration.journal.col.debit").text}</th>
                <th scope="col" className="integration-number">{t("integration.journal.col.credit").text}</th>
              </tr>
            </thead>
            <tbody>
              {r.accounts.map((account) => (
                <tr key={account.role}>
                  <td>{account.role}</td>
                  <td className="integration-number">
                    <MoneyDisplay amount={String(account.debit)} />
                  </td>
                  <td className="integration-number">
                    <MoneyDisplay amount={String(account.credit)} />
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">{t("integration.reconciliation.total").text}</th>
                <th className="integration-number">
                  <MoneyDisplay amount={String(r.totalDebit)} />
                </th>
                <th className="integration-number">
                  <MoneyDisplay amount={String(r.totalCredit)} />
                </th>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
    </section>
  );
}
