import { AuthProviderProps } from "react-oidc-context";

export const oidcConfig: AuthProviderProps = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY || "http://localhost:8080/realms/coop",
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID || "coop-erp-web",
  redirect_uri: window.location.origin + "/",
  response_type: "code",
  scope: "openid profile email",
  post_logout_redirect_uri: window.location.origin + "/"
};
