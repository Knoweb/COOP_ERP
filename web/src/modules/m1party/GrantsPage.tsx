import { useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { PageHeader } from "../../shell/components/PageHeader";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useAdminApi } from "./adminApi";
import type { ExternalGrant } from "./adminApi";
import { usePartyApi } from "./partyApi";
import { GRANT_STATUS_LOOK, REVOKE_REASONS, refusalOf } from "./adminView";
import { CustomSelect } from "./CustomSelect";
import { CustomDatePicker } from "./CustomDatePicker";

/**
 * The Federation's register of external grants (21A M1-09; doc 21 section 4.6): read-only
 * access for an auditor or a ministry officer to named entities, until a date. The grantee is
 * a user of kind EXTERNAL; the server caps the end date (m1.external_grant.max_months) and
 * refuses what breaks a rule with its own words. Revoking ends a grant now, with a reason.
 */
export function GrantsPage() {
  const t = useT();
  const formatInstant = useFormatInstant();
  const api = useAdminApi();
  const party = usePartyApi();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();

  const [grantee, setGrantee] = useState("");
  const [entity, setEntity] = useState("");
  const [until, setUntil] = useState("");
  const [reason, setReason] = useState("");
  const [revoking, setRevoking] = useState<ExternalGrant | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const grants = useQuery({ queryKey: ["party", "grants"], queryFn: () => api.listGrants() });
  const externals = useQuery({ queryKey: ["party", "users", "EXTERNAL"], queryFn: () => api.listUsers({ userKind: "EXTERNAL" }) });
  const societies = useQuery({ queryKey: ["party", "societies", "grant-picker"], queryFn: () => party.listSocieties({}) });

  const userName = new Map((externals.data?.items ?? []).map((user) => [user.userId, `${user.displayName} (${user.username})`]));
  const entityName = new Map((societies.data?.items ?? []).map((society) => [society.entityId, `${society.entityCode} ${society.legalNameEn}`]));

  const onProblem = (error: unknown) => {
    if (error instanceof ApiProblem) {
      idempotencyKey.next();
    }
  };
  const afterCommand = (messageId: string) => {
    idempotencyKey.next();
    setDone(messageId);
    queryClient.invalidateQueries({ queryKey: ["party", "grants"] });
  };
  const grant = useMutation({
    mutationFn: () =>
      api.grantExternalView(
        // The end of the chosen day, in UTC: the grant holds through that day.
        { granteeUserId: grantee, scopeEntityIds: entity ? [entity] : [], validUntil: `${until}T23:59:59Z`, reason: reason.trim() },
        idempotencyKey.current()
      ),
    onSuccess: () => {
      afterCommand("party.grants.granted");
      setGrantee("");
      setEntity("");
      setUntil("");
      setReason("");
    },
    onError: onProblem
  });
  const revoke = useMutation({
    mutationFn: ({ grantId, text }: { grantId: string; text: string }) => api.revokeExternalView(grantId, text, idempotencyKey.current()),
    onSuccess: () => {
      setRevoking(null);
      afterCommand("party.grants.revoked");
    },
    onError: onProblem
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setDone(null);
    grant.mutate();
  };
  const refusal = grant.error ?? revoke.error;
  const rows = grants.data ?? [];

  return (
    <main className="shell-page party-grants-page">
      <PageHeader icon="society" title={t("party.grants.title").text} />
      {grants.isLoading && <p>{t("party.list.loading").text}</p>}
      {grants.isError && <p role="alert">{refusalOf(grants.error, t("party.error.generic").text).text}</p>}
      {grants.isSuccess && rows.length === 0 && (
        <div className="party-empty-card">
          <p className="party-empty-text">{t("party.grants.empty").text}</p>
        </div>
      )}

      {rows.length > 0 && (
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.grants.grantee").text}</th>
                  <th scope="col">{t("party.grants.entities").text}</th>
                  <th scope="col">{t("party.grants.from").text}</th>
                  <th scope="col">{t("party.grants.until").text}</th>
                  <th scope="col">{t("party.col.status").text}</th>
                  <th scope="col">
                    <span className="visually-hidden">{t("party.roles.actions").text}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.grantId}>
                    <td>{userName.get(row.granteeUserId) ?? row.granteeUserId}</td>
                    <td>{row.scopeEntityIds.map((id) => entityName.get(id) ?? id).join(", ")}</td>
                    <td>{formatInstant(row.validFrom)}</td>
                    <td>{formatInstant(row.validUntil)}</td>
                    <td>
                      <StateChip state={GRANT_STATUS_LOOK[row.status]} label={t(`party.grants.status.${row.status}`).text} />
                    </td>
                    <td>
                      {row.status === "ACTIVE" && (
                        <button type="button" className="modern-btn" onClick={() => setRevoking(row)} disabled={revoke.isPending}>
                          {t("party.grants.revoke").text}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {revoking && (
        <ReasonCapture
          title={t("party.grants.revoke.question").text}
          codes={REVOKE_REASONS.map((code) => ({ code, label: t(`party.revoke.reason.${code}`).text }))}
          onConfirm={(reasonCode, reasonText) =>
            revoke.mutate({ grantId: revoking.grantId, text: reasonText ? `${reasonCode}: ${reasonText}` : reasonCode })
          }
          onCancel={() => setRevoking(null)}
          pending={revoke.isPending}
        />
      )}

      <div className="party-grant-card">
        <div className="party-grant-card__header">
          <div className="party-grant-card__icon-badge">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
          <div className="party-grant-card__titles">
            <h2 id="party-grant-title" className="party-grant-card__title">{t("party.grants.new").text}</h2>
            {externals.isSuccess && externals.data.items.length === 0 && (
              <p role="note" className="party-grant-card__note">{t("party.grants.no_external").text}</p>
            )}
          </div>
        </div>

        <form onSubmit={submit} className="party-grant-form" aria-labelledby="party-grant-title">
          <div className="party-grant-form__grid">
            <label className="party-form-field">
              <span className="party-form-field__label">{t("party.grants.grantee").text}</span>
              <CustomSelect
                value={grantee}
                onChange={(val) => setGrantee(val)}
                ariaLabel={t("party.grants.grantee").text}
                placeholder={t("party.roles.choose").text}
                options={[
                  { value: "", label: t("party.roles.choose").text },
                  ...(externals.data?.items ?? []).map((user) => ({
                    value: user.userId,
                    label: `${user.displayName} (${user.username})`
                  }))
                ]}
              />
            </label>

            <label className="party-form-field">
              <span className="party-form-field__label">{t("party.grants.entities").text}</span>
              <CustomSelect
                value={entity}
                onChange={(val) => setEntity(val)}
                ariaLabel={t("party.grants.entities").text}
                placeholder={t("party.roles.choose").text}
                options={(societies.data?.items ?? []).map((society) => ({
                  value: society.entityId,
                  label: `${society.entityCode} ${society.legalNameEn}`
                }))}
              />
            </label>

            <label className="party-form-field">
              <span className="party-form-field__label">{t("party.grants.until").text}</span>
              <CustomDatePicker
                value={until}
                onChange={(val) => setUntil(val)}
                ariaLabel={t("party.grants.until").text}
                required
              />
            </label>

            <label className="party-form-field party-form-field--full">
              <span className="party-form-field__label">{t("party.grants.reason").text}</span>
              <textarea
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                rows={2}
                required
                maxLength={500}
                placeholder={t("party.grants.reason").text}
              />
            </label>
          </div>

          <div className="party-grant-form__footer">
            <button
              type="submit"
              className="party-grant-submit-btn"
              disabled={!grantee || !entity || !until || !reason.trim() || grant.isPending}
            >
              <svg viewBox="0 0 20 20" width="16" height="16" fill="currentColor" aria-hidden="true">
                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
              </svg>
              <span>{t("party.grants.submit").text}</span>
            </button>
          </div>
        </form>
      </div>

      {done && <p role="status">{t(done).text}</p>}
      {refusal != null && <p role="alert">{refusalOf(refusal, t("party.error.generic").text).text}</p>}
    </main>
  );
}
