import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Session } from "../auth/session";
import { messages } from "../i18n/messages";
import type { Locale } from "../i18n/messages";
import { ScopeBanner } from "./ScopeBanner";
import { ScopeProvider, scopesOf } from "./ScopeContext";
import { useScope } from "./useScope";

const MPCS = "0190f000-0000-7000-8000-000000000002";

// The banner reads the signed-in user through useSession(); the tests decide who that is.
let session: Session | null = null;
vi.mock("../auth/session", () => ({ useSession: () => session }));

// The entity's name (M1, gov.entity.view) comes through the resolved permission set and the
// party API; the tests decide both without a server.
let permissions: {
  policyClass: string;
  permissions: string[];
  activeScope?: { entityId: string; locationId?: string | null } | null;
} | null = null;
vi.mock("../auth/PermissionsContext", () => ({ usePermissions: () => permissions }));

let entityApi: ReturnType<typeof vi.fn>;
const clientOptions: unknown[] = [];
vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return {
    ...actual,
    useApiClient: (options?: unknown) => {
      clientOptions.push(options);
      return { GET: entityApi };
    }
  };
});

function signedIn(overrides: Partial<Session>): Session {
  return { userId: "u-1", displayName: "Sunil Perera", entityId: MPCS, policyClass: "OWN", language: "en", ...overrides };
}

function renderBanner(locale: Locale = "en") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale={locale} messages={messages[locale]}>
        <ScopeProvider>
          <ScopeBanner />
        </ScopeProvider>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("the scope of a session", () => {
  it("is the entity and the policy class of the token, for the whole entity", () => {
    const { active } = scopesOf({ entityId: MPCS, policyClass: "OWN" });

    expect(active.entityId).toBe(MPCS);
    expect(active.policyClass).toBe("OWN");
    expect(active.locationId).toBeNull();      // no claim carries a location yet
    expect(active.entityName).toBeNull();      // entity names are M1's
    expect(active.seesData).toBe(true);
  });

  it("narrows to the one place a user holds, as the session read names it", () => {
    const shop = "0190f000-0000-7000-8000-000000000201";
    expect(scopesOf({ entityId: MPCS, policyClass: "OWN" }, shop).active.locationId).toBe(shop);
  });

  it("shortens the entity id to its end, where two ids created together differ", () => {
    expect(scopesOf({ entityId: MPCS, policyClass: "OWN" }).active.entityShortId).toBe("00000002");
  });

  it("is exactly one scope, so there is nothing to switch between", () => {
    const state = scopesOf({ entityId: MPCS, policyClass: "OWN" });
    expect(state.available).toEqual([state.active]);
  });

  it("sees no data without an entity, and none with the policy class NONE", () => {
    expect(scopesOf({ entityId: null, policyClass: "OWN" }).active.seesData).toBe(false);
    expect(scopesOf({ entityId: MPCS, policyClass: "NONE" }).active.seesData).toBe(false);
  });
});

describe("ScopeBanner", () => {
  beforeEach(() => {
    permissions = { policyClass: "OWN", permissions: [] };
    entityApi = vi.fn(async () => ({ data: null }));
  });
  afterEach(cleanup);

  it("says who is acting for which entity, at which locations, in which class", () => {
    session = signedIn({});
    renderBanner();

    const banner = screen.getByRole("region", { name: "Your scope" });
    expect(banner.textContent).toContain("Sunil Perera is acting for Entity …00000002");
    expect(banner.textContent).toContain("All locations");
    expect(banner.textContent).toContain("Own entity");
  });

  it("speaks the language of the user", () => {
    session = signedIn({ policyClass: "FEDERATION_VIEW" });
    renderBanner("ta");

    const banner = screen.getByRole("region");
    expect(banner.textContent).toContain("நிறுவனம் …00000002");
    expect(banner.textContent).toContain("சம்மேளனப் பார்வை");
  });

  it("writes a class other than OWN out in words, because colour alone is not a signal", () => {
    session = signedIn({ policyClass: "EXTERNAL_TIMEBOXED" });
    renderBanner();

    expect(screen.getByRole("region").textContent).toContain("External access for a limited time");
  });

  it("warns a user who has no entity that no data will be shown, instead of showing an empty banner", () => {
    session = signedIn({ entityId: null, policyClass: "NONE" });
    renderBanner();

    const warning = screen.getByRole("alert");
    expect(warning.textContent).toContain("Warning: you have no scope.");
    expect(warning.textContent).toContain("you will see no data");
    expect(screen.queryByRole("region")).toBeNull();
  });

  it("gives the same warning in Sinhala", () => {
    session = signedIn({ entityId: null, policyClass: "NONE" });
    renderBanner("si");

    expect(screen.getByRole("alert").textContent).toContain("ඔබට විෂය පථයක් නැත");
  });

  it("names the entity once the reader holds gov.entity.view, instead of the short id", async () => {
    session = signedIn({});
    permissions = { policyClass: "OWN", permissions: ["gov.entity.view"] };
    entityApi = vi.fn(async () => ({
      data: { entityId: MPCS, legalNameEn: "Ridigama MPCS", legalNameSi: "රිදිගම බ.ස.ස", legalNameTa: null }
    }));
    renderBanner();

    await waitFor(() => expect(screen.getByRole("region").textContent).toContain("Ridigama MPCS"));
    expect(screen.getByRole("region").textContent).not.toContain("Entity …00000002");
  });

  it("names the entity in the reader's language", async () => {
    session = signedIn({});
    permissions = { policyClass: "OWN", permissions: ["gov.entity.view"] };
    entityApi = vi.fn(async () => ({
      data: { entityId: MPCS, legalNameEn: "Ridigama MPCS", legalNameSi: "රිදිගම බ.ස.ස", legalNameTa: null }
    }));
    renderBanner("si");

    await waitFor(() => expect(screen.getByRole("region").textContent).toContain("රිදිගම බ.ස.ස"));
  });

  it("reads the entity at the one place a user holds, so a stores user is not refused (400 scope.invalid)", async () => {
    const warehouse = "0190f000-0000-7000-8000-000000000101";
    session = signedIn({});
    permissions = { policyClass: "OWN", permissions: ["gov.entity.view"], activeScope: { entityId: MPCS, locationId: warehouse } };
    entityApi = vi.fn(async () => ({ data: { entityId: MPCS, legalNameEn: "Ridigama MPCS" } }));
    clientOptions.length = 0;
    renderBanner();

    await waitFor(() => expect(screen.getByRole("region").textContent).toContain("Ridigama MPCS"));
    // The provider is outside its own context: its client is told the location it worked out.
    expect(clientOptions).toContainEqual({ locationId: warehouse });
  });

  it("keeps the short id when the reader has no gov.entity.view, without asking the server", () => {
    session = signedIn({});
    permissions = { policyClass: "OWN", permissions: [] };
    renderBanner();

    expect(screen.getByRole("region").textContent).toContain("Entity …00000002");
    expect(entityApi).not.toHaveBeenCalled();
  });
});

describe("useScope", () => {
  afterEach(cleanup);

  it("refuses to work outside the ScopeProvider, with a text that says where the provider is", () => {
    const Lost = () => <p>{useScope().entityId}</p>;
    // React and jsdom both print the error of a failed render; keep the test output readable.
    const quiet = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const swallow = (event: ErrorEvent) => event.preventDefault();
    window.addEventListener("error", swallow);

    expect(() => render(<Lost />)).toThrow(/outside <ScopeProvider>/);

    window.removeEventListener("error", swallow);
    quiet.mockRestore();
  });
});
