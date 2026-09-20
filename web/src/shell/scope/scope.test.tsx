import { cleanup, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
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

function signedIn(overrides: Partial<Session>): Session {
  return { userId: "u-1", displayName: "Sunil Perera", entityId: MPCS, policyClass: "OWN", language: "en", roles: [], ...overrides };
}

function renderBanner(locale: Locale = "en") {
  return render(
    <IntlProvider locale={locale} messages={messages[locale]}>
      <ScopeProvider>
        <ScopeBanner />
      </ScopeProvider>
    </IntlProvider>
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
