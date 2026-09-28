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
  const [entities, setEntities] = useState<string[]>([]);
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
        { granteeUserId: grantee, scopeEntityIds: entities, validUntil: `${until}T23:59:59Z`, reason: reason.trim() },
        idempotencyKey.current()
      ),
    onSuccess: () => {
      afterCommand("party.grants.granted");
      setGrantee("");
      setEntities([]);
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
    <main className="shell-page">
      <PageHeader icon="society" title={t("party.grants.title").text} />
      {grants.isLoading && <p>{t("party.list.loading").text}</p>}
      {grants.isError && <p role="alert">{refusalOf(grants.error, t("party.error.generic").text).text}</p>}
      {grants.isSuccess && rows.length === 0 && <p>{t("party.grants.empty").text}</p>}

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

      <form onSubmit={submit} className="party-form-row" aria-labelledby="party-grant-title">
        <h2 id="party-grant-title">{t("party.grants.new").text}</h2>
        {externals.isSuccess && externals.data.items.length === 0 && <p role="note">{t("party.grants.no_external").text}</p>}
        <label className="party-form-field">
          {t("party.grants.grantee").text}
          <select value={grantee} onChange={(event) => setGrantee(event.target.value)} required>
            <option value="">{t("party.roles.choose").text}</option>
            {(externals.data?.items ?? []).map((user) => (
              <option key={user.userId} value={user.userId}>
                {user.displayName} ({user.username})
              </option>
            ))}
          </select>
        </label>
        <label className="party-form-field">
          {t("party.grants.entities").text}
          <select
            multiple
            value={entities}
            onChange={(event) => setEntities(Array.from(event.target.selectedOptions, (option) => option.value))}
            required
          >
            {(societies.data?.items ?? []).map((society) => (
              <option key={society.entityId} value={society.entityId}>
                {society.entityCode} {society.legalNameEn}
              </option>
            ))}
          </select>
        </label>
        <label className="party-form-field">
          {t("party.grants.until").text}
          <input type="date" value={until} onChange={(event) => setUntil(event.target.value)} required />
        </label>
        <label className="party-form-field">
          {t("party.grants.reason").text}
          <textarea value={reason} onChange={(event) => setReason(event.target.value)} rows={2} required maxLength={500} />
        </label>
        <button type="submit" className="modern-btn" disabled={!grantee || entities.length === 0 || !until || !reason.trim() || grant.isPending}>
          {t("party.grants.submit").text}
        </button>
      </form>

      {done && <p role="status">{t(done).text}</p>}
      {refusal != null && <p role="alert">{refusalOf(refusal, t("party.error.generic").text).text}</p>}
    </main>
  );
}
