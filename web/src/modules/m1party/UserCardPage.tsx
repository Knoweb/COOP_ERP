import { useState } from "react";
import type { FormEvent } from "react";
import { useIntl } from "react-intl";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApprovalBar } from "../../shell/components/ApprovalBar";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { useAdminApi } from "./adminApi";
import type { Assignment, CredentialKind, CredentialReset, UserReason } from "./adminApi";
import { DEACTIVATE_REASONS, REVOKE_REASONS, USER_STATUS_LOOK, assignableRoles, locationName, refusalOf, roleName, userActions } from "./adminView";

/**
 * One user's card (21A section 8, "User card"; M1-07 and M1-08): who the user is, the
 * credential commands, deactivation, and the roles the user holds, entity-wide or at one
 * location, with assign and revoke. Every refusal of the server is shown in the server's own
 * words; a separation-of-duties refusal is marked as such, so the administrator knows it is a
 * rule about the PERSON (two duties one person may not hold together), not a fault.
 */
export function UserCardPage() {
  const t = useT();
  const { userId = "" } = useParams();
  const queryClient = useQueryClient();
  const api = useAdminApi();
  const idempotencyKey = useIdempotencyKey();
  const canManage = useHasPermission("gov.user.manage");
  const canAssign = useHasPermission("gov.role.manage");

  const [deactivating, setDeactivating] = useState(false);
  const [issued, setIssued] = useState<CredentialReset | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const user = useQuery({ queryKey: ["party", "user", userId], queryFn: () => api.getUser(userId), enabled: userId !== "" });

  const afterProblem = (error: unknown) => {
    if (error instanceof ApiProblem) {
      idempotencyKey.next();
    }
  };
  const reset = useMutation({
    mutationFn: (credential: CredentialKind) => api.resetCredential(userId, credential, idempotencyKey.current()),
    onSuccess: (result) => {
      idempotencyKey.next();
      setIssued(result);
      setDone(result.credential === "SECOND_FACTOR" ? "party.user.second_factor.done" : null);
      queryClient.invalidateQueries({ queryKey: ["party", "user", userId] });
      queryClient.invalidateQueries({ queryKey: ["party", "users"] });
    },
    onError: afterProblem
  });
  const deactivate = useMutation({
    mutationFn: (reason: UserReason) => api.deactivateUser(userId, reason, idempotencyKey.current()),
    onSuccess: () => {
      idempotencyKey.next();
      setDeactivating(false);
      setDone("party.user.deactivated");
      queryClient.invalidateQueries({ queryKey: ["party", "user", userId] });
      queryClient.invalidateQueries({ queryKey: ["party", "users"] });
    },
    onError: afterProblem
  });

  if (user.isLoading) {
    return (
      <main className="shell-page">
        <p>{t("party.list.loading").text}</p>
      </main>
    );
  }
  if (user.isError || !user.data) {
    return (
      <main className="shell-page">
        <BackToUsers />
        <p role="alert">{refusalOf(user.error, t("party.error.generic").text).text}</p>
      </main>
    );
  }

  const data = user.data;
  const actions = userActions(data, {
    canManage,
    t: (id) => t(id).text,
    busy: reset.isPending || deactivate.isPending,
    onPassword: () => {
      setDone(null);
      setIssued(null);
      reset.mutate("PASSWORD");
    },
    onSecondFactor: () => {
      setDone(null);
      setIssued(null);
      reset.mutate("SECOND_FACTOR");
    },
    onDeactivate: () => {
      setDone(null);
      setDeactivating(true);
    }
  });
  const commandError = reset.error ?? deactivate.error;

  return (
    <main className="shell-page">
      <BackToUsers />
      <DocumentHeader
        code={data.username}
        title={data.displayName}
        subtitle={t(`party.user.kind.${data.userKind}`).text}
        state={{ look: USER_STATUS_LOOK[data.status], label: t(`party.user.status.${data.status}`).text }}
        facts={[
          { label: t("party.field.language").text, value: t(`party.language.${data.language}`).text },
          { label: t("party.user.pin").text, value: t(data.pinSet ? "party.user.pin.set" : "party.user.pin.not_set").text }
        ]}
      >
        <ApprovalBar actions={actions} />
      </DocumentHeader>

      {deactivating && (
        <ReasonCapture
          title={t("party.user.deactivate.question").text}
          codes={DEACTIVATE_REASONS.map((code) => ({ code, label: t(`party.user.reason.${code}`).text }))}
          onConfirm={(reasonCode, reasonText) => deactivate.mutate({ reasonCode, reasonText })}
          onCancel={() => setDeactivating(false)}
          pending={deactivate.isPending}
        />
      )}

      {issued?.credential === "PASSWORD" && (
        <section className="modern-table-card party-form-row" aria-labelledby="party-password-title">
          <h2 id="party-password-title">{t("party.user.password.issued").text}</h2>
          {issued.temporaryPassword ? (
            <>
              <p>{t("party.user.password.once").text}</p>
              <p>
                <code aria-label={t("party.user.password.value").text}>{issued.temporaryPassword}</code>
              </p>
            </>
          ) : (
            <p>{t(issued.delivery === "NOTIFIED" ? "party.user.password.notified" : "party.user.password.not_repeated").text}</p>
          )}
        </section>
      )}

      {done && <p role="status">{t(done).text}</p>}
      {commandError !== null && <p role="alert">{refusalOf(commandError, t("party.error.generic").text).text}</p>}

      {canAssign && <UserRoles userId={userId} deactivated={data.status === "DEACTIVATED"} />}
    </main>
  );
}

function BackToUsers() {
  const t = useT();
  return (
    <p>
      <Link className="back-link" to="/party/users">
        {t("party.users.back").text}
      </Link>
    </p>
  );
}

/**
 * The roles a user holds at the entity and where (M1-08): entity-wide or at one location. A
 * new assignment names the role and, optionally, the location; the server checks the
 * separation of duties across everything the user would then hold and refuses with
 * m1.assignment.sod_conflict when two duties clash.
 */
function UserRoles({ userId, deactivated }: { userId: string; deactivated: boolean }) {
  const t = useT();
  const { locale } = useIntl();
  const api = useAdminApi();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();

  const [roleId, setRoleId] = useState("");
  const [locationId, setLocationId] = useState("");
  const [revoking, setRevoking] = useState<Assignment | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const assignments = useQuery({ queryKey: ["party", "assignments", userId], queryFn: () => api.listAssignments(userId) });
  const roles = useQuery({ queryKey: ["party", "roles"], queryFn: () => api.listRoles() });
  const locations = useQuery({ queryKey: ["party", "locations", "admin"], queryFn: () => api.listLocations() });

  const roleById = new Map((roles.data ?? []).map((role) => [role.roleId, role]));
  const locationById = new Map((locations.data ?? []).map((location) => [location.locationId, location]));

  const refresh = () => {
    idempotencyKey.next();
    queryClient.invalidateQueries({ queryKey: ["party", "assignments", userId] });
  };
  const onProblem = (error: unknown) => {
    if (error instanceof ApiProblem) {
      idempotencyKey.next();
    }
  };
  const assign = useMutation({
    mutationFn: () => api.assignRole({ userId, roleId, scopeLocationId: locationId || null }, idempotencyKey.current()),
    onSuccess: () => {
      refresh();
      setRoleId("");
      setLocationId("");
      setDone("party.roles.assigned");
    },
    onError: onProblem
  });
  const revoke = useMutation({
    mutationFn: ({ assignment, reason }: { assignment: Assignment; reason: string }) =>
      api.revokeRole(
        { userId, roleId: assignment.roleId, scopeLocationId: assignment.scopeLocationId ?? null, reason },
        idempotencyKey.current()
      ),
    onSuccess: () => {
      refresh();
      setRevoking(null);
      setDone("party.roles.revoked");
    },
    onError: onProblem
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setDone(null);
    assign.mutate();
  };

  const refusal = assign.error ?? revoke.error;
  const shownRefusal = refusal ? refusalOf(refusal, t("party.error.generic").text) : null;
  const held = assignments.data ?? [];

  return (
    <section aria-labelledby="party-user-roles" className="party-form-row-margin">
      <h2 id="party-user-roles">{t("party.roles.held").text}</h2>
      {assignments.isLoading && <p>{t("party.list.loading").text}</p>}
      {assignments.isSuccess && held.length === 0 && <p>{t("party.roles.none").text}</p>}
      {held.length > 0 && (
        <div className="modern-table-card">
          <table className="modern-table">
            <thead>
              <tr>
                <th scope="col">{t("party.roles.role").text}</th>
                <th scope="col">{t("party.roles.where").text}</th>
                <th scope="col">
                  <span className="visually-hidden">{t("party.roles.actions").text}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {held.map((assignment) => {
                const role = roleById.get(assignment.roleId);
                const location = assignment.scopeLocationId ? locationById.get(assignment.scopeLocationId) : undefined;
                const roleLabel = role ? roleName(role, locale) : assignment.roleId;
                return (
                  <tr key={`${assignment.roleId}-${assignment.scopeLocationId ?? "entity"}`}>
                    <td>{roleLabel}</td>
                    <td>
                      {assignment.scopeLocationId
                        ? location
                          ? locationName(location, locale)
                          : assignment.scopeLocationId
                        : t("party.roles.entity_wide").text}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="modern-btn"
                        aria-label={t("party.roles.revoke.of", undefined, { role: roleLabel }).text}
                        onClick={() => {
                          setDone(null);
                          setRevoking(assignment);
                        }}
                        disabled={revoke.isPending}
                      >
                        {t("party.roles.revoke").text}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {revoking && (
        <ReasonCapture
          title={t("party.roles.revoke.question").text}
          codes={REVOKE_REASONS.map((code) => ({ code, label: t(`party.revoke.reason.${code}`).text }))}
          onConfirm={(reasonCode, reasonText) => revoke.mutate({ assignment: revoking, reason: reasonText ? `${reasonCode}: ${reasonText}` : reasonCode })}
          onCancel={() => setRevoking(null)}
          pending={revoke.isPending}
        />
      )}

      {!deactivated && (
        <form onSubmit={submit} className="party-form-row" aria-labelledby="party-assign-title">
          <h3 id="party-assign-title">{t("party.roles.assign.title").text}</h3>
          <label className="party-form-field">
            {t("party.roles.role").text}
            <select aria-label={t("party.roles.role").text} value={roleId} onChange={(event) => setRoleId(event.target.value)} required>
              <option value="">{t("party.roles.choose").text}</option>
              {assignableRoles(roles.data ?? []).map((role) => (
                <option key={role.roleId} value={role.roleId}>
                  {roleName(role, locale)}
                </option>
              ))}
            </select>
          </label>
          <label className="party-form-field">
            {t("party.roles.where").text}
            <select aria-label={t("party.roles.where").text} value={locationId} onChange={(event) => setLocationId(event.target.value)}>
              <option value="">{t("party.roles.entity_wide").text}</option>
              {(locations.data ?? []).map((location) => (
                <option key={location.locationId} value={location.locationId}>
                  {locationName(location, locale)}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="modern-btn" disabled={!roleId || assign.isPending}>
            {t("party.roles.assign").text}
          </button>
        </form>
      )}

      {done && <p role="status">{t(done).text}</p>}
      {shownRefusal && (
        <div role="alert">
          {shownRefusal.sod && <strong>{t("party.roles.sod.title").text} </strong>}
          <span>{shownRefusal.text}</span>
        </div>
      )}
    </section>
  );
}
