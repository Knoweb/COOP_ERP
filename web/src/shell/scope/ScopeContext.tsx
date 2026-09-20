// The scope the signed-in user is acting in: for which entity, at which location, in which
// policy class. The banner shows it on every page (ScopeBanner.tsx), so that nobody records a
// document for the wrong society. Screens read it with useScope().
//
// Where it comes from today: the claims of the access token, through useSession(). The dev
// realm (infra/compose/realm-dev.json) issues `ent` and `cls` and NO `scopes` claim, so a
// user has exactly ONE scope and there is NO scope switcher. Doc 30 section 3 asks for a
// switcher "only for users with more than one scope" (a Federation officer, an accountant of
// several societies); that needs the scope assignments of M1 and the claim mapping of 19A
// K-02. The shape below is ready for it: `available` is a list, and the switcher will be the
// one place that changes `active`.
//
// The scope here is what is SHOWN. What the user may read is decided by the server: row-level
// security filters every query by the scope of the verified token.

import { createContext, useMemo, type ReactNode } from "react";
import { useSession } from "../auth/session";
import type { PolicyClass, Session } from "../auth/session";

export type Scope = {
  /** The entity the user acts for; null when the token names none. */
  entityId: string | null;
  /**
   * The name of the entity; null until M1 exists. Entities are M1's data and the token
   * carries only the id, so for now the banner shows `entityShortId` instead.
   */
  entityName: string | null;
  /** The end of the entity id, enough to tell two entities apart on the screen; null without an entity. */
  entityShortId: string | null;
  /** The location the scope is narrowed to; always null for now (no claim carries one): the whole entity. */
  locationId: string | null;
  policyClass: PolicyClass;
  /**
   * False when this scope can show no data at all: no entity, or the policy class NONE. The
   * server answers such a user with empty lists, which looks like "there is nothing" when it
   * means "you may see nothing". The banner therefore warns, loudly.
   */
  seesData: boolean;
};

export type ScopeState = {
  /** The scope every request is made in. */
  active: Scope;
  /** Every scope the user has. One entry today; see the note at the top of this file. */
  available: Scope[];
};

/**
 * How many characters of the entity id the banner shows. The END of the id, not the start:
 * ids are UUIDv7, which begin with the time of creation, so entities created together begin
 * alike and differ at the end.
 */
const SHORT_ID_LENGTH = 8;

/** The scopes a session gives. A plain function, so that it can be tested without a browser. */
export function scopesOf(session: Pick<Session, "entityId" | "policyClass">): ScopeState {
  const active: Scope = {
    entityId: session.entityId,
    entityName: null,
    entityShortId: session.entityId ? session.entityId.slice(-SHORT_ID_LENGTH) : null,
    locationId: null,
    policyClass: session.policyClass,
    seesData: session.entityId !== null && session.policyClass !== "NONE"
  };
  return { active, available: [active] };
}

/** null outside a ScopeProvider; useScope() turns that into an error with a clear text. */
export const ScopeContext = createContext<ScopeState | null>(null);

/** Put once around the pages by the shell (router.tsx). Must be inside RequireLogin. */
export function ScopeProvider({ children }: { children: ReactNode }) {
  const session = useSession();
  const entityId = session?.entityId ?? null;
  const policyClass = session?.policyClass ?? "NONE";

  // Worked out again only when the entity or the class changes, not on every token renewal.
  const state = useMemo(() => scopesOf({ entityId, policyClass }), [entityId, policyClass]);

  return <ScopeContext.Provider value={state}>{children}</ScopeContext.Provider>;
}
