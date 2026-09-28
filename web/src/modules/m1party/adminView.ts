// How the administration screens of the party module show users, roles, assignments and
// external grants: chip looks, names in the user's language, which roles can be assigned, and
// the actions of a user's card. Pure functions, so the screens and the tests agree.

import { ApiProblem } from "../../shell/api/client";
import type { ApprovalAction } from "../../shell/components/ApprovalBar";
import type { ChipState } from "../../shell/components/StateChip";
import { inLocale } from "../../shell/i18n/localName";
import type { Assignment, ExternalGrant, Location, Role, User, UserStatus } from "./adminApi";

/** The chip look of each user status (21A section 4, the user lifecycle). */
export const USER_STATUS_LOOK: Record<UserStatus, ChipState> = {
  PENDING: "draft",
  ACTIVE: "issued",
  LOCKED: "alert",
  DEACTIVATED: "void"
};

export const USER_STATUSES: UserStatus[] = ["PENDING", "ACTIVE", "LOCKED", "DEACTIVATED"];

/** The chip look of each grant status. */
export const GRANT_STATUS_LOOK: Record<ExternalGrant["status"], ChipState> = {
  ACTIVE: "issued",
  EXPIRED: "void",
  REVOKED: "void"
};

/**
 * The reason codes a deactivation may carry. The API takes any code; these are the module's
 * own vocabulary and their words are party.user.reason.<code> in the catalogue.
 */
export const DEACTIVATE_REASONS = ["LEFT", "ROLE_ENDED", "SECURITY", "OTHER"] as const;

/** The reason codes of a revocation of a role or a grant; words are party.revoke.reason.<code>. */
export const REVOKE_REASONS = ["DUTIES_CHANGED", "LEFT", "SECURITY", "OTHER"] as const;

/** A role's name in the reader's language, English when not translated. */
export function roleName(role: Pick<Role, "nameEn" | "nameSi" | "nameTa">, locale: string): string {
  return inLocale(locale, role.nameEn, role.nameSi, role.nameTa);
}

/** A location's code and name in the reader's language. */
export function locationName(location: Pick<Location, "locationCode" | "nameEn" | "nameSi" | "nameTa">, locale: string): string {
  return `${location.locationCode} ${inLocale(locale, location.nameEn, location.nameSi, location.nameTa)}`;
}

/**
 * The roles an administrator can give a user of the entity: the entity's own roles that are
 * in force. A federation template is a starting point for a role, not a role anybody holds
 * (21A section 4.3: it is cloned first), and a retired role takes no new holder; the server
 * refuses both (m1.assignment.role_not_in_scope, m1.role.retired), so they are not offered.
 */
export function assignableRoles(roles: Role[]): Role[] {
  return roles.filter((role) => !role.template && role.status === "ACTIVE" && role.ownerEntityId);
}

/** Two assignments are the same when user, role and location are the same (entity-wide = no location). */
export function sameAssignment(a: Assignment, b: Pick<Assignment, "userId" | "roleId" | "scopeLocationId">): boolean {
  return a.userId === b.userId && a.roleId === b.roleId && (a.scopeLocationId ?? null) === (b.scopeLocationId ?? null);
}

/**
 * What a refusal of the server says, in the words the server chose for its message id (the
 * problem document's title, already in the user's language). A separation-of-duties refusal
 * (m1.assignment.sod_conflict, m1.role.sod_conflict) is the one the assignment screen must
 * show plainly: the title says it; the id is kept for the screen to mark it.
 */
export function refusalOf(error: unknown, fallback: string): { text: string; code: string | null; sod: boolean } {
  if (error instanceof ApiProblem) {
    const code = error.problem.code ?? null;
    return {
      text: error.problem.title || fallback,
      code,
      sod: code === "m1.assignment.sod_conflict" || code === "m1.role.sod_conflict"
    };
  }
  return { text: fallback, code: null, sod: false };
}

/**
 * The actions of a user's card for its status. A DEACTIVATED user has none: deactivation is
 * for good (21A M1-07; the slice has no reactivate), and the successor is a new user. PENDING
 * and LOCKED are brought to ACTIVE by issuing a password.
 */
export function userActions(
  user: Pick<User, "status" | "userKind">,
  wiring: {
    canManage: boolean;
    t: (id: string) => string;
    busy: boolean;
    onPassword: () => void;
    onSecondFactor: () => void;
    onDeactivate: () => void;
  }
): ApprovalAction[] {
  if (!wiring.canManage || user.status === "DEACTIVATED") {
    return [];
  }
  const { t } = wiring;
  // A till-only user signs in with a PIN at the till, never with a password (21A M1-07).
  const passwordReason = user.userKind === "TILL" ? t("party.user.password.till_only") : undefined;
  return [
    {
      id: "password",
      label: t("party.user.password"),
      primary: user.status !== "ACTIVE",
      disabledReason: passwordReason,
      pending: wiring.busy,
      onClick: wiring.onPassword
    },
    { id: "second-factor", label: t("party.user.second_factor"), disabledReason: passwordReason, pending: wiring.busy, onClick: wiring.onSecondFactor },
    { id: "deactivate", label: t("party.user.deactivate"), pending: wiring.busy, onClick: wiring.onDeactivate }
  ];
}
