import { AuthProvider } from "react-oidc-context";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { oidcConfig } from "./shell/auth/oidc";
import { RequireLogin } from "./shell/auth/RequireLogin";
import { useSession } from "./shell/auth/session";
import { chooseLocale, messages } from "./shell/i18n/messages";
import { router } from "./router";

const queryClient = new QueryClient();

/**
 * The language follows the user (doc 30 section 3): the `lang` claim of the token, so the
 * cashier of a Tamil shop opens the back office in Tamil without choosing anything. Before
 * login there is no user yet; the "signing in" screen then uses ?lang= or English.
 */
function Localised({ children }: { children: React.ReactNode }) {
  const locale = chooseLocale(useSession()?.language ?? null, window.location.search);
  return (
    <IntlProvider locale={locale} messages={messages[locale]}>
      {children}
    </IntlProvider>
  );
}

export function App() {
  return (
    <AuthProvider {...oidcConfig}>
      <Localised>
        <RequireLogin>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </RequireLogin>
      </Localised>
    </AuthProvider>
  );
}
