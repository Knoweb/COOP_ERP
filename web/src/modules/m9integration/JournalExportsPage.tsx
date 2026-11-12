import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useIntegrationApi, type JournalExport } from "./integrationApi";
import { defaultExportPeriod, errorText, journalFileName, periodIsOpen } from "./integrationView";
import { IntegrationDatePicker } from "./IntegrationDatePicker";
import "./integration.css";

/**
 * Journal exports (29A section 8, demo scope): the period form with what waits for the next
 * export, "Generate", and the run history with the totals, the file for the accounting package
 * and the reconciliation of each export. Every amount goes through MoneyDisplay; the page adds
 * nothing up itself, the server's reconciliation does.
 *
 * Wave 2 (CR-29-1): the default period ends yesterday; a period that ends today or later is still
 * open, so the provisional box is ticked and required for it (the server refuses the export
 * otherwise); a provisional export is marked in the history; and the banner says when postings
 * for periods already exported are waiting, so the accountant exports the supplement.
 */
export function JournalExportsPage() {
  const t = useT();
  const api = useIntegrationApi();
  const queryClient = useQueryClient();
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const canExport = useHasPermission("int.journal.export");
  const exportKey = useIdempotencyKey();
  const canAcknowledge = useHasPermission("int.journal.ack");
  const acknowledgeKey = useIdempotencyKey();
  const lastAcknowledgeAttempt = useRef<string | null>(null);

  const today = businessToday();
  const [period, setPeriod] = useState(() => defaultExportPeriod(today));
  const [provisional, setProvisional] = useState(false);
  const [message, setMessage] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [acknowledgingId, setAcknowledgingId] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const periodValid = period.from !== "" && period.to !== "" && period.from <= period.to;
  const periodOpen = periodIsOpen(period.to, today);
  // An open period ticks the box; the box stays required, so unticking it stops the form, and the
  // accountant chooses knowingly between a provisional file and an earlier end date.
  useEffect(() => {
    if (periodOpen) {
      setProvisional(true);
    }
  }, [periodOpen]);
  const pending = useQuery({
    queryKey: ["integration", "pending", period.from, period.to],
    queryFn: () => api.pending(period.from, period.to),
    enabled: periodValid
  });
  const supplementDue = useQuery({
    queryKey: ["integration", "supplement-due"],
    queryFn: () => api.supplementDue()
  });
  const exports = useQuery({ queryKey: ["integration", "exports"], queryFn: () => api.exports() });

  async function generate() {
    setMessage(null);
    setBusy(true);
    try {
      const created = await api.requestExport(period.from, period.to, provisional, exportKey.current());
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

  async function acknowledge(journal: JournalExport) {
    setMessage(null);

    // Retry the same action with the same key after a network failure.
    // Choosing another export starts a new action with a new key.
    if (lastAcknowledgeAttempt.current !== journal.exportId) {
      acknowledgeKey.next();
      lastAcknowledgeAttempt.current = journal.exportId;
    }

    setAcknowledgingId(journal.exportId);
    try {
      await api.acknowledgeExport(journal.exportId, acknowledgeKey.current());
      acknowledgeKey.next();
      lastAcknowledgeAttempt.current = null;
      setMessage({ kind: "status", text: t("integration.journal.acknowledged").text });
      await queryClient.invalidateQueries({ queryKey: ["integration", "exports"] });
    } catch (error) {
      setMessage({
        kind: "alert",
        text: errorText(error, t("integration.error.generic").text)
      });
    } finally {
      setAcknowledgingId(null);
    }
  }

  async function download(journal: JournalExport) {
    setMessage(null);
    try {
      const text = await api.file(journal.exportId);
      const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = journalFileName(journal.periodFrom, journal.periodTo, journal.provisional);
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      setMessage({ kind: "alert", text: errorText(error, t("integration.error.generic").text) });
    }
  }

  return (
    <main className="shell-page journal-exports-page">
      <PageHeader
        icon={
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <path d="M14 2v6h6" />
            <path d="M8 13h8" />
            <path d="M8 17h5" />
          </svg>
        }
        title={t("integration.journal.title").text}
      />
      <p className="journal-exports-subtitle">{t("integration.journal.intro").text}</p>

      <form
        className="modern-filter-panel integration-bar journal-controls"
        onSubmit={(event) => {
          event.preventDefault();
          void generate();
        }}
      >
        <div className="journal-controls__row">
          <div className="modern-field integration-period-field">
            <label htmlFor="integration-period-from" className="modern-field__label integration-label">
              {t("integration.field.from").text}
            </label>
            <IntegrationDatePicker
              id="integration-period-from"
              ariaLabel={t("integration.field.from").text}
              value={period.from}
              required
              onChange={(from) => setPeriod({ ...period, from })}
            />
          </div>
          <div className="modern-field integration-period-field">
            <label htmlFor="integration-period-to" className="modern-field__label integration-label">
              {t("integration.field.to").text}
            </label>
            <IntegrationDatePicker
              id="integration-period-to"
              ariaLabel={t("integration.field.to").text}
              value={period.to}
              required
              onChange={(to) => setPeriod({ ...period, to })}
            />
          </div>
          <label className={`modern-field integration-check${provisional ? " integration-check--checked" : ""}`}>
            <input
              type="checkbox"
              checked={provisional}
              required={periodOpen}
              aria-describedby="integration-provisional-hint"
              onChange={(event) => setProvisional(event.target.checked)}
            />
            <span className="modern-field__label integration-label journal-check-label">
              {t("integration.journal.provisional").text}
            </span>
          </label>
          {canExport && (
            <div className="journal-controls__submit">
              <button
                type="submit"
                className="modern-btn modern-btn--primary journal-generate-btn"
                disabled={busy || !periodValid || pending.data?.postings === 0}
              >
                <svg className="journal-btn-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
                <span>{busy ? t("integration.journal.generating").text : t("integration.journal.generate").text}</span>
              </button>
            </div>
          )}
        </div>

        <p id="integration-provisional-hint" className="integration-hint journal-controls__hint">
          <svg className="journal-callout__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 16v-4" />
            <path d="M12 8h.01" />
          </svg>
          <span>
            {periodOpen
              ? t("integration.journal.provisional.open", undefined, { to: formatDate(period.to) }).text
              : t("integration.journal.provisional.hint").text}
          </span>
        </p>
      </form>

      <div className="journal-callouts">
        {supplementDue.data && supplementDue.data.postings > 0 && supplementDue.data.upTo && (
          <p role="status" className="integration-banner journal-callout journal-callout--warning">
            <svg className="journal-callout__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z" />
              <line x1="12" y1="9" x2="12" y2="13" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
            <span className="journal-callout__text">
              {
                t("integration.journal.supplement_due", undefined, {
                  count: String(supplementDue.data.postings),
                  upTo: formatDate(supplementDue.data.upTo)
                }).text
              }
            </span>
            <span className="journal-callout__amount">
              <MoneyDisplay amount={String(supplementDue.data.amount)} />
            </span>
          </p>
        )}
        {pending.data && (
          <p
            role="status"
            aria-live="polite"
            className={`journal-callout ${pending.data.postings === 0 ? "journal-callout--muted" : "journal-callout--pending"}`}
          >
            <svg className="journal-callout__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 7v5l3 2" />
            </svg>
            {pending.data.postings === 0 ? (
              <span className="journal-callout__text">{t("integration.journal.pending.none").text}</span>
            ) : (
              <>
                <span className="journal-callout__text">
                  {t("integration.journal.pending", undefined, { count: String(pending.data.postings) }).text}
                </span>
                <span className="journal-callout__amount">
                  <MoneyDisplay amount={String(pending.data.amount)} />
                </span>
              </>
            )}
          </p>
        )}
        {message && (
          <p
            role={message.kind}
            className={`journal-callout ${message.kind === "alert" ? "journal-callout--alert" : "journal-callout--success"}`}
          >
            <svg className="journal-callout__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              {message.kind === "alert" ? (
                <>
                  <circle cx="12" cy="12" r="9" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </>
              ) : (
                <>
                  <circle cx="12" cy="12" r="9" />
                  <path d="m9 12 2 2 4-4" />
                </>
              )}
            </svg>
            <span className="journal-callout__text">{message.text}</span>
          </p>
        )}
      </div>

      <section className="journal-section">
        <div className="journal-section__header">
          <div className="journal-section__title-group">
            <span className="journal-section__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
                <path d="M3 3v5h5" />
                <path d="M12 7v5l3 2" />
              </svg>
            </span>
            <h2>{t("integration.journal.history").text}</h2>
          </div>
          {exports.data && <span className="journal-count-pill">{exports.data.length}</span>}
        </div>
        {exports.isLoading && <p className="journal-state-text">{t("integration.loading").text}</p>}
        {exports.isError && (
          <p role="alert" className="journal-callout journal-callout--alert">
            {errorText(exports.error, t("integration.error.generic").text)}
          </p>
        )}
        {exports.data && exports.data.length === 0 && (
          <div className="integration-empty-card journal-empty-card">
            <p>{t("integration.journal.history.empty").text}</p>
          </div>
        )}
        {exports.data && exports.data.length > 0 && (
          <div className="modern-table-card journal-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table journal-exports-table">
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
                      <td className="journal-period-cell">
                        {formatDate(journal.periodFrom)} – {formatDate(journal.periodTo)}
                      </td>
                      <td className="journal-instant-cell">{formatInstant(journal.generatedAt)}</td>
                      <td>
                        <div className="journal-status-group">
                          <StateChip state="issued" label={t(`integration.journal.status.${journal.status}`).text} />
                          {journal.provisional && (
                            <StateChip state="alert" label={t("integration.journal.mark.PROVISIONAL").text} />
                          )}
                        </div>
                      </td>
                      <td className="integration-number journal-num-cell">{journal.lineCount}</td>
                      <td className="integration-number journal-num-cell">
                        <MoneyDisplay amount={String(journal.totalDebit)} />
                      </td>
                      <td className="integration-number journal-num-cell">
                        <MoneyDisplay amount={String(journal.totalCredit)} />
                      </td>
                      <td className="journal-actions-cell">
                        <div className="integration-actions">
                          {canExport && (
                            <button
                              type="button"
                              className="journal-row-btn journal-row-btn--secondary"
                              aria-label={t("integration.journal.download.label", undefined, {
                                from: formatDate(journal.periodFrom),
                                to: formatDate(journal.periodTo)
                              }).text}
                              onClick={() => void download(journal)}
                            >
                              {t("integration.journal.download").text}
                            </button>
                          )}
                          {canAcknowledge && journal.status === "GENERATED" && (
                            <button
                              type="button"
                              className="journal-row-btn journal-row-btn--primary"
                              disabled={acknowledgingId !== null}
                              onClick={() => void acknowledge(journal)}
                            >
                              {acknowledgingId === journal.exportId
                                ? t("integration.journal.acknowledging").text
                                : t("integration.journal.acknowledge").text}
                            </button>
                          )}
                          <button
                            type="button"
                            className={`journal-row-btn journal-row-btn--secondary${open === journal.exportId ? " journal-row-btn--active" : ""}`}
                            aria-expanded={open === journal.exportId}
                            onClick={() => setOpen(open === journal.exportId ? null : journal.exportId)}
                          >
                            {t("integration.journal.reconcile").text}
                          </button>
                        </div>
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
    return <p className="journal-state-text">{t("integration.loading").text}</p>;
  }
  if (reconciliation.isError || !reconciliation.data) {
    return (
      <p role="alert" className="journal-callout journal-callout--alert">
        {errorText(reconciliation.error, t("integration.error.generic").text)}
      </p>
    );
  }
  const r = reconciliation.data;
  const sound = r.balanced && r.matchesRecordedTotals && r.matchesRecordedHash;
  return (
    <section className="journal-section journal-reconciliation-panel" aria-labelledby="integration-reconciliation">
      <div className="journal-section__header">
        <div className="journal-section__title-group">
          <span className="journal-section__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              <polyline points="9 12 11 14 15 10" />
            </svg>
          </span>
          <h2 id="integration-reconciliation">{t("integration.reconciliation.title").text}</h2>
        </div>
      </div>
      <p
        role="status"
        className={`journal-reconciliation-status ${sound ? "journal-reconciliation-status--ok" : "journal-reconciliation-status--warn"}`}
      >
        <StateChip
          state={sound ? "issued" : "alert"}
          label={sound ? t("integration.reconciliation.balanced").text : t("integration.reconciliation.unbalanced").text}
        />
        <span>
          {r.matchesRecordedHash
            ? t("integration.reconciliation.hash.ok").text
            : t("integration.reconciliation.hash.changed").text}
        </span>
      </p>
      <div className="modern-table-card journal-reconciliation-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table journal-reconciliation-table">
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
                  <td title={account.role}>{t(`integration.account_role.${account.role}`, account.role).text}</td>
                  <td className="integration-number journal-num-cell">
                    <MoneyDisplay amount={String(account.debit)} />
                  </td>
                  <td className="integration-number journal-num-cell">
                    <MoneyDisplay amount={String(account.credit)} />
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">{t("integration.reconciliation.total").text}</th>
                <th className="integration-number journal-num-cell">
                  <MoneyDisplay amount={String(r.totalDebit)} />
                </th>
                <th className="integration-number journal-num-cell">
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
