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

const AUTHORITY = import.meta.env.VITE_OIDC_AUTHORITY || "http://localhost:8085/realms/coop";
const CLIENT_ID = import.meta.env.VITE_OIDC_CLIENT_ID || "coop-erp-web";

/** Where the browser was before it left for the login page; the state of the login request. */
export type LoginState = { returnTo?: string };

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
  // and &state=...; take them out, and go back to the page the user had asked for.
  onSigninCallback: (user) => {
    const returnTo = (user?.state as LoginState | undefined)?.returnTo;
    window.history.replaceState({}, document.title, returnTo || window.location.pathname);
  }
};
