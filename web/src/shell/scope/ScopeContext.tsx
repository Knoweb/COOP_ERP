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
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useApiClient } from "../api/client";
import { usePermissions } from "../auth/PermissionsContext";
import { hasPermission } from "../auth/permissions";
import { useSession } from "../auth/session";
import type { PolicyClass, Session } from "../auth/session";
import type { paths as partyPaths } from "../../generated/m1party";

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
  /** The location the scope is narrowed to: the one place of a user who holds one place only, as the session read names it; null for the whole entity. */
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
export function scopesOf(
  session: Pick<Session, "entityId" | "policyClass">,
  locationId: string | null = null,
  entityName: string | null = null
): ScopeState {
  const active: Scope = {
    entityId: session.entityId,
    entityName,
    entityShortId: session.entityId ? session.entityId.slice(-SHORT_ID_LENGTH) : null,
    locationId,
    policyClass: session.policyClass,
    seesData: session.entityId !== null && session.policyClass !== "NONE"
  };
  return { active, available: [active] };
}

/** The entity's name in the reader's language, en falling back when si/ta is missing. */
function localNameOf(
  entity: { legalNameEn: string; legalNameSi?: string | null; legalNameTa?: string | null },
  locale: string
): string {
  if (locale === "si" && entity.legalNameSi) {
    return entity.legalNameSi;
  }
  if (locale === "ta" && entity.legalNameTa) {
    return entity.legalNameTa;
  }
  return entity.legalNameEn;
}

/** null outside a ScopeProvider; useScope() turns that into an error with a clear text. */
export const ScopeContext = createContext<ScopeState | null>(null);

/** Put once around the pages by the shell (router.tsx). Must be inside RequireLogin. */
export function ScopeProvider({ children }: { children: ReactNode }) {
  const session = useSession();
  const entityId = session?.entityId ?? null;
  const policyClass = session?.policyClass ?? "NONE";
  const permissions = usePermissions();
  const intl = useIntl();

  // Worked out again only when the entity or the class changes, not on every token renewal.
  // A user who holds one place only acts there: the session read names it (PermissionsContext).
  const active = permissions?.activeScope;
  const locationId = active && active.entityId === entityId ? (active.locationId ?? null) : null;
  // This provider is outside its own context, so its client is told the location: without it the
  // entity read of a user who holds one place only named the entity alone and was refused (400).
  const party = useApiClient<partyPaths>({ locationId });

  // The banner names the entity when the user holds gov.entity.view for it (M1); otherwise it
  // keeps the honest short id (ScopeBanner.tsx). Read once per entity, not on every render.
  const canReadEntity = permissions !== null && hasPermission(permissions, "gov.entity.view");
  const entityQuery = useQuery({
    queryKey: ["shell", "scope-entity", entityId, locationId],
    enabled: entityId !== null && canReadEntity,
    staleTime: Infinity,
    queryFn: async () => {
      const { data } = await party.GET("/v1/party/entities/{entityId}", { params: { path: { entityId: entityId! } } });
      return data ?? null;
    }
  });
  const entityName = entityQuery.data ? localNameOf(entityQuery.data, intl.locale) : null;

  const state = useMemo(
    () => scopesOf({ entityId, policyClass }, locationId, entityName),
    [entityId, policyClass, locationId, entityName]
  );

  return <ScopeContext.Provider value={state}>{children}</ScopeContext.Provider>;
}
