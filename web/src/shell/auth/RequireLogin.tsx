import { useEffect, type ReactNode } from "react";
import { hasAuthParams, useAuth } from "react-oidc-context";
import { useT } from "../i18n/useT";
import type { LoginState } from "./oidc";

/**
 * Nothing of the back office is shown to somebody who is not signed in. Wrapped around the
 * whole application: an anonymous visitor is sent to the identity server, and comes back to
 * the page that was asked for.
 *
 * It never shows a login form of its own: the password is typed on the identity server's
 * page and nowhere else (authorization code flow with PKCE).
 */
export function RequireLogin({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const t = useT();

  const mustSignIn =
    !auth.isAuthenticated && !auth.isLoading && !auth.activeNavigator && !auth.error && !hasAuthParams();

  useEffect(() => {
    if (mustSignIn) {
      const state: LoginState = { returnTo: window.location.pathname + window.location.search };
      void auth.signinRedirect({ state });
    }
  }, [mustSignIn, auth]);

  if (auth.error) {
    // The identity server is down, the clock is wrong, the login was cancelled ... Say so,
    // and offer the one thing that can help. Never loop back to the login page by itself.
    return (
      <main className="auth-screen" role="alert">
        <h1>{t("shell.auth.failed").text}</h1>
        <p>{auth.error.message}</p>
        <button type="button" onClick={() => void auth.signinRedirect()}>
          {t("shell.auth.try_again").text}
        </button>
      </main>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <main className="auth-screen" aria-busy="true">
        <p>{t("shell.auth.signing_in").text}</p>
      </main>
    );
  }

  return <>{children}</>;
}
