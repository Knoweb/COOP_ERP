// What the signed-in user may see. The navigation, the route guard and a screen that hides a
// button all ask here, through hasPermission() or useHasPermission(); nothing else in the web
// client looks at permissions, and nothing at all looks at the token's roles.
//
// This decides what is SHOWN, never what is ALLOWED. The server checks the permission on
// every request and stays the authority (doc 30 section 3: "every action button also handles
// the server's permission denial gracefully"). Hiding a button is a courtesy to the user, not
// a security measure.
//
// Where the set comes from (doc 30 section 3, "visibility from the resolved permission set";
// decided 27 September 2026, CR-19A-8): the kernel resolves the permissions of the user in the
// active scope (19A K-03b, PermissionResolver) and hands them to the client through
// GET /v1/session (openapi/session.yaml). PermissionsContext.tsx reads it once per token; the
// functions below only look at what it returned. The temporary role map that stood here until
// then is gone: a role the map did not know held nothing, and the map had to be edited by hand.

import type { PolicyClass } from "./session";
import { usePermissions } from "./PermissionsContext";

/** The resolved permission set of a user, with the class that says whether commands are open at all. */
export type PermissionSet = {
  policyClass: PolicyClass;
  /** The permission codes the server resolved for the active scope. */
  permissions: readonly string[];
};

/**
 * The permission codes that name a read, by the catalogue's convention (gov.entity.view,
 * cat.sku.view, hello.greeting.read). Everything else is a command.
 */
const READ_SUFFIXES = [".view", ".read"];

function isRead(permission: string): boolean {
  return READ_SUFFIXES.some((suffix) => permission.endsWith(suffix));
}

/**
 * Does this user hold the permission? A code the server did not return is not held.
 *
 * One rule of the server is mirrored here as well, so that a screen never offers a command
 * the server would refuse by class alone: only the OWN class runs a command (19A section 3;
 * kernel PermissionGate). A user in FEDERATION_VIEW or EXTERNAL_TIMEBOXED is shown the reads
 * the server resolved and no command, even where a read and a command share one code.
 */
export function hasPermission(set: PermissionSet, permission: string): boolean {
  if (set.policyClass !== "OWN" && !isRead(permission)) {
    return false;
  }
  return set.permissions.includes(permission);
}

/** Does this user hold at least one of them? An empty list asks for nothing, so: yes. */
export function hasAnyPermission(set: PermissionSet, permissions: string[]): boolean {
  return permissions.length === 0 || permissions.some((permission) => hasPermission(set, permission));
}

/**
 * For a screen: `const canRegister = useHasPermission("hello.greeting.register")`. False while
 * the set is still being read: a button appears when the answer is known, never before.
 */
export function useHasPermission(permission: string): boolean {
  const set = usePermissions();
  return set !== null && hasPermission(set, permission);
}
