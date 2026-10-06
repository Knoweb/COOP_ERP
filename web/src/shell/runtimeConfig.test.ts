import { afterEach, describe, expect, it } from "vitest";
import { pageConfig, resolveConfig } from "./runtimeConfig";

describe("runtime configuration", () => {
  afterEach(() => {
    delete window.__COOP_ERP_CONFIG__;
  });

  it("uses the defaults of the local stack when nothing is set", () => {
    expect(resolveConfig({}, {})).toEqual({
      apiBase: "http://localhost:8080",
      oidcAuthority: "http://localhost:8085/realms/coop",
      oidcClientId: "coop-erp-web",
      stepUpAcrValues: ""
    });
  });

  it("uses the build-time VITE_* values on a developer machine", () => {
    const env = {
      VITE_API_BASE: "http://localhost:8080",
      VITE_OIDC_AUTHORITY: "http://localhost:8085/realms/coop",
      VITE_OIDC_CLIENT_ID: "another-client",
      VITE_OIDC_STEP_UP_ACR_VALUES: "mfa"
    };
    expect(resolveConfig({}, env)).toEqual({
      apiBase: "http://localhost:8080",
      oidcAuthority: "http://localhost:8085/realms/coop",
      oidcClientId: "another-client",
      stepUpAcrValues: "mfa"
    });
  });

  it("lets the server's /config.js win over the build", () => {
    const page = {
      apiBase: "https://demo.example.com/api",
      oidcAuthority: "https://demo.example.com/auth/realms/coop",
      oidcClientId: "coop-erp-web",
      stepUpAcrValues: ""
    };
    const env = { VITE_API_BASE: "http://localhost:8080", VITE_OIDC_STEP_UP_ACR_VALUES: "mfa" };
    expect(resolveConfig(page, env)).toEqual(page);
  });

  it("treats an empty server value as unset, but an empty acr list as a choice", () => {
    const resolved = resolveConfig({ apiBase: " ", stepUpAcrValues: "" }, { VITE_API_BASE: "http://x", VITE_OIDC_STEP_UP_ACR_VALUES: "mfa" });
    expect(resolved.apiBase).toBe("http://x");
    expect(resolved.stepUpAcrValues).toBe("");
  });

  it("reads the object /config.js put on the window, and ignores anything else", () => {
    expect(pageConfig()).toEqual({});
    window.__COOP_ERP_CONFIG__ = { apiBase: "https://a.example/api" };
    expect(pageConfig()).toEqual({ apiBase: "https://a.example/api" });
    window.__COOP_ERP_CONFIG__ = "nonsense" as never;
    expect(pageConfig()).toEqual({});
  });
});
