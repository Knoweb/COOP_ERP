import { AuthProvider } from "react-oidc-context";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { oidcConfig } from "./shell/auth/oidc";
import { messages } from "./shell/i18n/messages";
import { router } from "./router";

const queryClient = new QueryClient();

export function App() {
  const locale = "en"; // Default locale

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
