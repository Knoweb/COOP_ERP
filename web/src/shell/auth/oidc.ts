// Login configuration: OpenID Connect, authorization code flow with PKCE (doc 30 section 3;
// 17A section 7). The library (oidc-client-ts) uses PKCE by itself for response_type "code".
//
// Where the tokens live: in memory only (doc 30: "access token in memory"). Not in
// localStorage or sessionStorage, where any script on the page could read them. The price is
// that a page reload forgets the session; the identity server still has its own session
// cookie, so the reload is answered by a redirect that comes straight back, without a login
// form. Tokens are renewed in the background before they expire.

import { InMemoryWebStorage, WebStorageStateStore } from "oidc-client-ts";
import type { AuthProviderProps } from "react-oidc-context";
import type { PendingCommand } from "../api/pendingCommand";

const AUTHORITY = import.meta.env.VITE_OIDC_AUTHORITY || "http://localhost:8085/realms/coop";
const CLIENT_ID = import.meta.env.VITE_OIDC_CLIENT_ID || "coop-erp-web";

/**
 * What the step-up asks the identity server for, as `acr_values` (doc 19 section 2.2: the
 * second factor at the action). A realm with a second factor names its authentication
 * context here (for example "loa2" or "mfa", one of the values the backend accepts in
 * `coop-erp.security.mfa.acr-values`); the server then counts `auth_time` as the second
 * factor when the token's `acr` names it. Empty by default: the development realm of
 * infra/compose has no OTP, and there a fresh password sign-in counts (`prompt=login` plus the
 * backend's `password-reauth-counts`), so nothing is asked for beyond the sign-in itself.
 */
export const STEP_UP_ACR_VALUES: string = import.meta.env.VITE_OIDC_STEP_UP_ACR_VALUES || "";

/**
 * Where the browser was before it left for the login page, and, after a step-up, the command
 * the server refused with mfa.required, taken again once on return (StepUpReplay.tsx). The
 * state of the login request; the library keeps it in session storage until the browser is
 * back, so a page unload does not lose it.
 */
export type LoginState = { returnTo?: string; pendingCommand?: PendingCommand };

// The command the sign-in brought back, until the shell takes it: set by the callback below,
// read once by takePendingCommand(). A module variable, because the callback runs before any
// component of the shell exists.
let broughtBack: PendingCommand | null = null;

/** The command to replay after this sign-in, once; null when there is none (or it was taken). */
export function takePendingCommand(): PendingCommand | null {
  const pending = broughtBack;
  broughtBack = null;
  return pending;
}

export const oidcConfig: AuthProviderProps = {
  authority: AUTHORITY,
  client_id: CLIENT_ID,
  redirect_uri: window.location.origin + "/",
  post_logout_redirect_uri: window.location.origin + "/",
  response_type: "code",
  scope: "openid profile",
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true,

  // After the identity server sends the browser back, the address still carries ?code=...
  // and &state=...; take them out, and go back to the page the user had asked for, with the
  // command a step-up interrupted, if there was one.
  onSigninCallback: (user) => {
    const state = (user?.state as LoginState | undefined) ?? {};
    broughtBack = state.pendingCommand ?? null;
    window.history.replaceState({}, document.title, state.returnTo || window.location.pathname);
  }
};
