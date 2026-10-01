// Where the web client finds the API and the identity server, read when the page loads rather
// than when the bundle is built, so that one published bundle serves any server (infra/deploy).
//
// A deployed server answers /config.js with
//   window.__COOP_ERP_CONFIG__ = { apiBase: "...", oidcAuthority: "...", ... };
// written from its own address when the web container starts (infra/deploy/Caddyfile), and
// index.html loads that script before the application. On a developer machine the file is
// web/public/config.js, which names nothing, so the VITE_* variables of infra/compose (and their
// defaults in client.ts and oidc.ts) apply exactly as before.
//
// The rule, per setting: a non-empty value from /config.js wins; otherwise the build-time
// VITE_* value; otherwise the caller's default. A value set to "" in /config.js counts as
// unset, except for the step-up acr values, where "" is a real choice ("ask for nothing");
// that one is taken whenever the key is present.

export type RuntimeConfig = {
  apiBase?: string;
  oidcAuthority?: string;
  oidcClientId?: string;
  stepUpAcrValues?: string;
};

declare global {
  interface Window {
    __COOP_ERP_CONFIG__?: RuntimeConfig;
  }
}

type BuildEnv = Record<string, string | boolean | undefined>;

/** The settings of /config.js, or none (a test, or a page without the script). */
export function pageConfig(): RuntimeConfig {
  if (typeof window === "undefined") return {};
  const config = window.__COOP_ERP_CONFIG__;
  return config && typeof config === "object" ? config : {};
}

function text(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : undefined;
}

/**
 * The settings in force. `page` and `env` are parameters so that a test can name them; the
 * application calls it with none.
 */
export function resolveConfig(
  page: RuntimeConfig = pageConfig(),
  env: BuildEnv = import.meta.env
): Required<RuntimeConfig> {
  return {
    apiBase: text(page.apiBase) ?? text(env.VITE_API_BASE) ?? "http://localhost:8080",
    oidcAuthority: text(page.oidcAuthority) ?? text(env.VITE_OIDC_AUTHORITY) ?? "http://localhost:8085/realms/coop",
    oidcClientId: text(page.oidcClientId) ?? text(env.VITE_OIDC_CLIENT_ID) ?? "coop-erp-web",
    stepUpAcrValues:
      typeof page.stepUpAcrValues === "string"
        ? page.stepUpAcrValues.trim()
        : (text(env.VITE_OIDC_STEP_UP_ACR_VALUES) ?? "")
  };
}
