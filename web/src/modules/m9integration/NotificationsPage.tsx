import { useState } from "react";
import { useIntl } from "react-intl";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { useScope } from "../../shell/scope/useScope";
import { useIntegrationApi, type LogStatus, type NotificationLogEntry, type NotificationRule } from "./integrationApi";
import { errorText, templateText } from "./integrationView";
import { deferredUntil, recipientParty } from "./notificationLogView";
import "./integration.css";

const LOG_STATUSES: LogStatus[] = ["QUEUED", "SENT", "FAILED", "SUPPRESSED"];

const LOG_LOOK: Record<LogStatus, ChipState> = {
  QUEUED: "draft",
  SENT: "issued",
  FAILED: "alert",
  SUPPRESSED: "void"
};

/**
 * Notifications (29A section 8, demo scope): the rules with their status (the Federation's
 * administration activates and retires them), the templates in the reader's language with the
 * English fallback tag, and the delivery log, which names the recipient's entity and role and
 * never a number or a hash a reader could reverse (wave 2, M9-07).
 */
export function NotificationsPage() {
  const t = useT();
  const scope = useScope();
  const api = useIntegrationApi();
  const intl = useIntl();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const canManage = useHasPermission("int.notify.manage");
  const ruleKey = useIdempotencyKey();
  const language = (["si", "ta"].includes(intl.locale) ? intl.locale : "en") as "en" | "si" | "ta";

  const [status, setStatus] = useState<LogStatus | "">("");
  const [message, setMessage] = useState<string | null>(null);

  const rules = useQuery({ queryKey: ["integration", "rules"], queryFn: () => api.rules() });
  const templates = useQuery({ queryKey: ["integration", "templates"], queryFn: () => api.templates() });
  const log = useQuery({
    queryKey: ["integration", "log", status],
    queryFn: () => api.log(status === "" ? undefined : status)
  });

  /** "Entity …000000b2, the accounts desk · 3fa9c2e1": who was reached, never the number (M9-07). */
  function recipientText(entry: NotificationLogEntry): string {
    const party = recipientParty(entry, scope.entityId);
    const parts: string[] = [];
    if (party.kind === "own") {
      parts.push(t("integration.log.recipient.own").text);
    } else if (party.kind === "other") {
      parts.push(t("integration.log.recipient.other", undefined, { id: party.shortId }).text);
    }
    if (entry.audienceRole) {
      parts.push(t(`integration.role.${entry.audienceRole}`, entry.audienceRole).text);
    }
    const who = parts.join(", ");
    return entry.recipientTag ? (who ? `${who} · ${entry.recipientTag}` : entry.recipientTag) : who;
  }

  /** The reason a row was not sent, or when quiet hours let it go (CR-19A-12). */
  function noteText(entry: NotificationLogEntry): string {
    const until = deferredUntil(entry, new Date());
    if (until) {
      return t("integration.log.deferred", undefined, { until: formatInstant(until) }).text;
    }
    return entry.suppressedReason ?? entry.lastError ?? "";
  }

  async function toggle(rule: NotificationRule) {
    setMessage(null);
    try {
      await api.setRuleStatus(rule.ruleId, rule.status !== "ACTIVE", ruleKey.current());
      ruleKey.next();
      await queryClient.invalidateQueries({ queryKey: ["integration", "rules"] });
    } catch (error) {
      setMessage(errorText(error, t("integration.error.generic").text));
    }
  }

  return (
    <main className="shell-page">
      <h1>{t("integration.notify.title").text}</h1>
      {message && <p role="alert">{message}</p>}

      <section>
        <h2>{t("integration.rules.title").text}</h2>
        {rules.isLoading && <p>{t("integration.loading").text}</p>}
        {rules.isError && <p role="alert">{errorText(rules.error, t("integration.error.generic").text)}</p>}
        {rules.data && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th scope="col">{t("integration.rules.col.name").text}</th>
                    <th scope="col">{t("integration.rules.col.event").text}</th>
                    <th scope="col">{t("integration.rules.col.audience").text}</th>
                    <th scope="col">{t("integration.rules.col.channels").text}</th>
                    <th scope="col">{t("integration.rules.col.status").text}</th>
                    {canManage && <th scope="col">{t("integration.journal.col.actions").text}</th>}
                  </tr>
                </thead>
                <tbody>
                  {rules.data.map((rule) => (
                    <tr key={rule.ruleId}>
                      <td>{rule.name}</td>
                      <td title={rule.eventType}>{t(`integration.event.${rule.eventType}`, rule.eventType).text}</td>
                      <td>
                        {t(`integration.audience.${rule.audienceKind}`, undefined, {
                          role: rule.audienceRole ? t(`integration.role.${rule.audienceRole}`, rule.audienceRole).text : ""
                        }).text}
                      </td>
                      <td>{rule.channels.map((channel) => t(`integration.channel.${channel}`, channel).text).join(", ")}</td>
                      <td>
                        <StateChip
                          state={rule.status === "ACTIVE" ? "issued" : rule.status === "DRAFT" ? "draft" : "void"}
                          label={t(`integration.status.${rule.status}`).text}
                        />
                      </td>
                      {canManage && (
                        <td>
                          {rule.federationWide && (
                            <button
                              type="button"
                              className="modern-btn"
                              aria-label={t(
                                rule.status === "ACTIVE" ? "integration.rules.retire.label" : "integration.rules.activate.label",
                                undefined,
                                { name: rule.name }
                              ).text}
                              onClick={() => void toggle(rule)}
                            >
                              {rule.status === "ACTIVE"
                                ? t("integration.rules.retire").text
                                : t("integration.rules.activate").text}
                            </button>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      <section>
        <h2>{t("integration.templates.title").text}</h2>
        {templates.isLoading && <p>{t("integration.loading").text}</p>}
        {templates.data && (
          <ul className="integration-templates">
            {templates.data.map((template) => {
              const subject = templateText(template.subjectEn, template.subjectSi, template.subjectTa, language);
              const body = templateText(template.bodyEn, template.bodySi, template.bodyTa, language);
              return (
                <li key={template.templateId} className="modern-table-card integration-template">
                  <h3>
                    {template.templateId} <span className="integration-muted">({template.channel})</span>
                  </h3>
                  {subject.text && <p className="integration-subject">{subject.text}</p>}
                  <p>
                    {body.text}
                    {body.fallback && <span className="integration-muted"> [EN]</span>}
                  </p>
                  <p className="integration-muted">
                    {t("integration.templates.placeholders", undefined, { names: template.placeholders.join(", ") }).text}
                  </p>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section>
        <h2>{t("integration.log.title").text}</h2>
        <div className="modern-filter-panel integration-bar">
          <label className="modern-field">
            <span className="modern-field__label integration-label">{t("integration.log.filter").text}</span>
            <div className="modern-select">
              <select
                aria-label={t("integration.log.filter").text}
                value={status}
                onChange={(event) => setStatus(event.target.value as LogStatus | "")}
              >
                <option value="">{t("integration.log.filter.all").text}</option>
                {LOG_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {t(`integration.log.status.${s}`).text}
                  </option>
                ))}
              </select>
              <svg className="modern-select__arrow" viewBox="0 0 24 24">
                <path d="m7 9 5 5 5-5" />
              </svg>
            </div>
          </label>
        </div>
        {log.isLoading && <p>{t("integration.loading").text}</p>}
        {log.isError && <p role="alert">{errorText(log.error, t("integration.error.generic").text)}</p>}
        {log.data && log.data.length === 0 && <p>{t("integration.log.empty").text}</p>}
        {log.data && log.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th scope="col">{t("integration.log.col.when").text}</th>
                    <th scope="col">{t("integration.log.col.template").text}</th>
                    <th scope="col">{t("integration.log.col.channel").text}</th>
                    <th scope="col">{t("integration.log.col.recipient").text}</th>
                    <th scope="col">{t("integration.log.col.language").text}</th>
                    <th scope="col">{t("integration.log.col.status").text}</th>
                    <th scope="col" className="integration-number">{t("integration.log.col.attempts").text}</th>
                    <th scope="col">{t("integration.log.col.note").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {log.data.map((entry) => (
                    <tr key={entry.notificationId}>
                      <td>{formatInstant(entry.createdAt)}</td>
                      <td title={entry.templateId ?? undefined}>{entry.templateId ? t(`integration.template.${entry.templateId}`, entry.templateId).text : ""}</td>
                      <td>{t(`integration.channel.${entry.channel}`, entry.channel).text}</td>
                      <td>{recipientText(entry)}</td>
                      <td>{entry.language ? t(`integration.language.${entry.language}`, entry.language).text : ""}</td>
                      <td>
                        <StateChip state={LOG_LOOK[entry.status]} label={t(`integration.log.status.${entry.status}`).text} />
                      </td>
                      <td className="integration-number">{entry.attempts}</td>
                      <td>{noteText(entry)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}
