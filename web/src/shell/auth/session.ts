// Who is signed in, as the rest of the client sees it. Screens and the API client use
// useSession(); nothing outside shell/auth touches the OIDC library or a raw token.
//
// The facts come from the claims of the access token (doc 19 section 1): sub, ent (home
// entity), cls (policy class), lang, roles. The dev realm of infra/compose issues them; 19A
// K-02 makes the backend read the same claims from the same token.

import { useMemo } from "react";
import { useAuth } from "react-oidc-context";
import type { Locale } from "../i18n/messages";

export type PolicyClass = "OWN" | "PARTY" | "FEDERATION_VIEW" | "EXTERNAL_TIMEBOXED" | "NONE";

export type Session = {
  userId: string;
  /** What to call the user on the screen: the full name when the token has one. */
  displayName: string;
  /** The entity the user belongs to (claim `ent`); null when the token names none. */
  entityId: string | null;
  policyClass: PolicyClass;
  /** The user's language (claim `lang`); null when the token names none we support. */
  language: Locale | null;
  roles: string[];
};

const POLICY_CLASSES: PolicyClass[] = ["OWN", "PARTY", "FEDERATION_VIEW", "EXTERNAL_TIMEBOXED"];
const LANGUAGES: Locale[] = ["en", "si", "ta"];

/** The payload of a JWT. Read for display and routing only: the server is what verifies it. */
function claimsOf(accessToken: string): Record<string, unknown> {
  const payload = accessToken.split(".")[1];
  if (!payload) {
    throw new Error("The access token is not a JWT");
  }
  const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
  const text = new TextDecoder().decode(Uint8Array.from(json, (c) => c.charCodeAt(0)));
  return JSON.parse(text) as Record<string, unknown>;
}

/**
 * The session an access token describes. Anything missing or unknown falls to the safe side:
 * no entity, policy class NONE (the server then shows nothing), no language (the shell then
 * uses English).
 */
export function sessionFromAccessToken(accessToken: string): Session {
  const claims = claimsOf(accessToken);
  const text = (name: string) => (typeof claims[name] === "string" ? (claims[name] as string) : null);

  const cls = text("cls") as PolicyClass | null;
  const lang = text("lang") as Locale | null;

  return {
    userId: text("sub") ?? "",
    displayName: text("name") ?? text("preferred_username") ?? "",
    entityId: text("ent"),
    policyClass: cls && POLICY_CLASSES.includes(cls) ? cls : "NONE",
    language: lang && LANGUAGES.includes(lang) ? lang : null,
    roles: Array.isArray(claims.roles) ? claims.roles.filter((r): r is string => typeof r === "string") : []
  };
}

/**
 * The signed-in user, or null before login has finished. Inside RequireLogin it is never null.
 * `accessToken` is for shell/api/client.ts; screens have no use for it.
 */
export function useSession(): (Session & { accessToken: string; signOut: () => void }) | null {
  const auth = useAuth();
  const token = auth.isAuthenticated ? auth.user?.access_token : undefined;
  const signOut = auth.signoutRedirect;

  // Decoded once per token, not once per render: a renewed token gives a new session.
  return useMemo(
    () => (token ? { ...sessionFromAccessToken(token), accessToken: token, signOut: () => void signOut() } : null),
    [token, signOut]
  );
}
