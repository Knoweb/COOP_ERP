// What the signed-in user may see. The navigation, the route guard and a screen that hides a
// button all ask here, through hasPermission() or useHasPermission(); nothing else in the web
// client looks at roles.
//
// This decides what is SHOWN, never what is ALLOWED. The server checks the permission on
// every request and stays the authority (doc 30 section 3: "every action button also handles
// the server's permission denial gracefully"). Hiding a button is a courtesy to the user, not
// a security measure.

import { useSession } from "./session";
import type { Session } from "./session";

/** In the temporary map below: this role holds every permission. */
const EVERY_PERMISSION = "*";

/**
 * TEMPORARY, until 19A K-03 (the permission resolver).
 *
 * The real rule (doc 19): a role is a set of permissions, maintained as data in M1, and the
 * kernel resolves the permissions of a user in a scope. Neither exists yet. The token of the
 * dev realm (infra/compose/realm-dev.json) carries `roles` only, so until K-03 the three
 * development roles are given their permissions here, by hand.
 *
 * K-03 replaces this map with the resolved permission set the server hands out; the two
 * functions below keep their signatures, so no caller changes. Do not add production roles
 * here, and do not grow this into a rule engine.
 *
 * fed-admin holds everything, so that a freshly scaffolded module shows up for one
 * development user without anybody editing the shell.
 */
const TEMPORARY_DEV_ROLE_PERMISSIONS: Record<string, string[]> = {
  "fed-admin": [EVERY_PERMISSION],
  "mpcs-admin": ["hello.greeting.read", "hello.greeting.register"],
  cashier: ["hello.greeting.read"]
};

/** Does this user hold the permission? A role the map does not know holds nothing. */
export function hasPermission(session: Pick<Session, "roles">, permission: string): boolean {
  return session.roles.some((role) => {
    const granted = TEMPORARY_DEV_ROLE_PERMISSIONS[role] ?? [];
    return granted.includes(EVERY_PERMISSION) || granted.includes(permission);
  });
}

/** Does this user hold at least one of them? An empty list asks for nothing, so: yes. */
export function hasAnyPermission(session: Pick<Session, "roles">, permissions: string[]): boolean {
  return permissions.length === 0 || permissions.some((permission) => hasPermission(session, permission));
}

/** For a screen: `const canRegister = useHasPermission("hello.greeting.register")`. */
export function useHasPermission(permission: string): boolean {
  const session = useSession();
  return session !== null && hasPermission(session, permission);
}
