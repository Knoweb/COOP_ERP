import { AuthProvider } from "react-oidc-context";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { oidcConfig } from "./shell/auth/oidc";
import { initialLocale, messages } from "./shell/i18n/messages";
import { router } from "./router";

const queryClient = new QueryClient();

export function App() {
  // The language comes from the address (?lang=si) until the shell reads it from the user's
  // token claim `lang` (S0-07, 19A K-02).
  const locale = initialLocale();

  return (
    <AuthProvider {...oidcConfig}>
      <IntlProvider locale={locale} messages={messages[locale]}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </IntlProvider>
    </AuthProvider>
  );
}
