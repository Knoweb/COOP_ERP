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
import { mayKeep, type PendingCommand } from "../api/pendingCommand";
import { resolveConfig } from "../runtimeConfig";

// From the server's /config.js, else the VITE_* values of the build (runtimeConfig.ts).
const CONFIG = resolveConfig();
const AUTHORITY = CONFIG.oidcAuthority;
const CLIENT_ID = CONFIG.oidcClientId;

/**
 * What the step-up asks the identity server for, as `acr_values` (doc 19 section 2.2: the
 * second factor at the action). A realm with a second factor names its authentication
 * context here (for example "loa2" or "mfa", one of the values the backend accepts in
 * `coop-erp.security.mfa.acr-values`); the server then counts `auth_time` as the second
 * factor when the token's `acr` names it. Empty by default: the development realm of
 * infra/compose has no OTP, and there a fresh password sign-in counts (`prompt=login` plus the
 * backend's `password-reauth-counts`), so nothing is asked for beyond the sign-in itself.
 */
export const STEP_UP_ACR_VALUES: string = CONFIG.stepUpAcrValues;

/**
 * Where the browser was before it left for the login page, and, after a step-up, the command
 * the server refused with mfa.required, taken again once on return (StepUpReplay.tsx). The
 * state of the login request; the library keeps it in session storage until the browser is
 * back, so a page unload does not lose it.
 */
export type LoginState = {
  returnTo?: string;
  pendingCommand?: PendingCommand;
  /** A command was refused for a fresh sign-in but must not be kept (personal data in its body, a file, a foreign URL). */
  commandNotKept?: boolean;
};

// The command the sign-in brought back, until the shell takes it: set by the callback below,
// read once by takePendingCommand(). A module variable, because the callback runs before any
// component of the shell exists.
let broughtBack: PendingCommand | null = null;
let notKept = false;

/** The command to replay after this sign-in, once; null when there is none (or it was taken). */
export function takePendingCommand(): PendingCommand | null {
  const pending = broughtBack;
  broughtBack = null;
  return pending;
}

/** True once after a sign-in that interrupted a command which was not kept: the person enters it again. */
export function takeCommandNotKept(): boolean {
  const flag = notKept;
  notKept = false;
  return flag;
}

/**
 * Whether a command the sign-in brought back may be taken again: its URL and body pass mayKeep,
 * and it was sent by the user who has just signed in (`sub`). A command with no subject is not
 * kept: nobody can tell whose it is.
 */
export function keepsFor(pending: PendingCommand, signedInSub: string | undefined, apiBase: string): boolean {
  return mayKeep(pending, apiBase) && !!pending.subject && pending.subject === signedInSub;
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
    // Checked again here: the state sat in session storage, where a crafted one could name any
    // URL. And kept only when the person who signed in is the one who sent the command: the
    // sign-in page lets anyone type a name, and on a shared PC that may be someone else.
    const kept = state.pendingCommand && keepsFor(state.pendingCommand, user?.profile.sub, CONFIG.apiBase) ? state.pendingCommand : null;
    broughtBack = kept;
    notKept = kept === null && (state.commandNotKept === true || state.pendingCommand !== undefined);
    window.history.replaceState({}, document.title, state.returnTo || window.location.pathname);
  }
};
