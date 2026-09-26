import { cleanup, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MODULES } from "./modules/registry";
import { shellRoutes } from "./router";
import type { Session } from "./shell/auth/session";
import { messages } from "./shell/i18n/messages";
import type { Locale } from "./shell/i18n/messages";
import type { ModuleDefinition } from "./shell/modules/ModuleDefinition";

// The shell reads the signed-in user through useSession(); the tests decide who that is.
let session: Session | null = null;
vi.mock("./shell/auth/session", () => ({ useSession: () => session }));

function signedInAs(role: string, language: Locale = "en"): Session {
  return {
    userId: "u-1", displayName: "Test User", entityId: "0190f000-0000-7000-8000-000000000002",
    policyClass: "OWN", language, roles: [role]
  };
}

// Two modules of the test's own, so that these tests say nothing about what hello contains.
// Their permissions are hello's, because those are what the temporary role map knows.
const greetings: ModuleDefinition = {
  id: "greetings",
  routes: [{ path: "greetings", element: <p>the greetings page</p> }],
  navItems: [{ labelId: "hello.nav", to: "/greetings" }],
  requiredPermissions: ["hello.greeting.read", "hello.greeting.register"]
};
const registerOnly: ModuleDefinition = {
  id: "register-only",
  routes: [{ path: "register-only", element: <p>the register-only page</p> }],
  navItems: [{ labelId: "hello.register", to: "/register-only" }],
  requiredPermissions: ["hello.greeting.register"]
};

function renderShell(modules: ModuleDefinition[], address: string, locale: Locale = "en") {
  const router = createMemoryRouter(shellRoutes(modules), { initialEntries: [address] });
  // The frame holds the step-up replay, which invalidates queries; App gives it the client.
  return render(
    <IntlProvider locale={locale} messages={messages[locale]}>
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </IntlProvider>
  );
}

describe("the shell assembled from module definitions", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllEnvs();
  });

  it("mounts the routes of every registered module under the banner and the navigation", () => {
    session = signedInAs("mpcs-admin");
    renderShell([greetings, registerOnly], "/register-only");

    expect(screen.getByRole("region", { name: "Your scope" })).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Modules" })).toBeTruthy();
    expect(screen.getByText("the register-only page")).toBeTruthy();
  });

  it("shows a module in the navigation to a role that holds one of its permissions", () => {
    session = signedInAs("cashier");
    renderShell([greetings, registerOnly], "/");

    const link = screen.getByRole("link", { name: "Greetings" });
    expect(link.getAttribute("href")).toBe("/greetings");
  });

  it("hides a module from a role that holds none of its permissions", () => {
    session = signedInAs("cashier");
    renderShell([greetings, registerOnly], "/");

    expect(screen.queryByRole("link", { name: "Register greeting" })).toBeNull();
  });

  it("labels the navigation in the language of the user", () => {
    session = signedInAs("cashier", "si");
    renderShell([greetings], "/", "si");

    expect(screen.getByRole("link", { name: "සුබපැතුම්" })).toBeTruthy();
  });

  it("says that there is no module to show, instead of an empty bar, to a user with no permission at all", () => {
    session = signedInAs("a-role-nobody-defined");
    renderShell([greetings, registerOnly], "/");

    expect(screen.getByRole("navigation").textContent).toContain("You have no permission for any module");
  });

  it("shows the not-allowed screen, never the page, when the address of a forbidden module is typed by hand", () => {
    session = signedInAs("cashier");
    renderShell([greetings, registerOnly], "/register-only");

    expect(screen.queryByText("the register-only page")).toBeNull();
    expect(screen.getByRole("alert").textContent).toContain("You are not allowed to open this page");
    // The frame stays: the user can see who they are and go somewhere else.
    expect(screen.getByRole("link", { name: "Greetings" })).toBeTruthy();
  });

  it("shows the not-allowed screen in Tamil to a Tamil user", () => {
    session = signedInAs("cashier", "ta");
    renderShell([registerOnly], "/register-only", "ta");

    expect(screen.getByRole("alert").textContent).toContain("இந்தப் பக்கத்தைத் திறக்க உங்களுக்கு அனுமதி இல்லை");
  });

  it("answers an address that no module owns with a sentence, not a blank page", () => {
    session = signedInAs("cashier");
    renderShell([greetings], "/no-such-page");

    expect(screen.getByText("Page not found")).toBeTruthy();
  });

  // Every real module groups its screens under one parent route that has no page of its own:
  // /party/entities, /party/locations. The guard must let the children through for a user who
  // may see the module, and must still stop everybody else.
  const grouped: ModuleDefinition = {
    id: "grouped",
    routes: [{ path: "party", children: [{ path: "entities", element: <p>the entities page</p> }] }],
    navItems: [{ labelId: "hello.nav", to: "/party/entities" }],
    requiredPermissions: ["hello.greeting.register"]
  };

  it("shows the screens under a parent route that has no page of its own", () => {
    session = signedInAs("mpcs-admin");
    renderShell([grouped], "/party/entities");

    expect(screen.getByText("the entities page")).toBeTruthy();
  });

  it("guards the screens under such a parent route as well", () => {
    session = signedInAs("cashier");
    renderShell([grouped], "/party/entities");

    expect(screen.queryByText("the entities page")).toBeNull();
    expect(screen.getByRole("alert").textContent).toContain("not allowed");
  });

  it("shows the design reference to any signed-in user, in their language, and keeps it out of the navigation", () => {
    // A cashier holds no permission that any module of this test asks for; the page needs none.
    session = signedInAs("a-role-nobody-defined", "ta");
    renderShell([greetings], "/_design", "ta");

    expect(screen.getByRole("heading", { level: 1, name: "வடிவமைப்புக் குறிப்பு" })).toBeTruthy();
    expect(screen.queryByRole("alert", { name: /அனுமதி/ })).toBeNull();
    expect(screen.queryAllByRole("link").map((link) => link.getAttribute("href"))).not.toContain("/_design");
  });

  it("shows on the design reference the contrast of every colour pair, measured from the real tokens", () => {
    session = signedInAs("cashier");
    renderShell([greetings], "/_design");

    // Body text on the page: #1f2933 on #ffffff. If this number changes, tokens.css changed.
    expect(screen.getAllByText("14.76:1").length).toBeGreaterThan(0);
    expect(screen.getByText("Rs 1,234,567.891")).toBeTruthy();
  });

  it("is silent about training outside training mode", () => {
    session = signedInAs("cashier");
    const { container } = renderShell([greetings], "/");

    expect(screen.queryByRole("region", { name: "TRAINING MODE" })).toBeNull();
    expect(container.querySelector(".shell--training")).toBeNull();
  });

  it("is loud in training mode: the band on top of every page and the frame around it", () => {
    vi.stubEnv("VITE_TRAINING_MODE", "true");
    session = signedInAs("cashier");
    const { container } = renderShell([greetings], "/greetings");

    expect(screen.getByRole("region", { name: "TRAINING MODE" })).toBeTruthy();
    expect(container.querySelector(".shell--training")).toBeTruthy();
    // The page is still there and usable: training mode changes how it looks, not what it does.
    expect(screen.getByText("the greetings page")).toBeTruthy();
  });

  it("refuses to start with two modules of the same id", () => {
    expect(() => shellRoutes([greetings, { ...registerOnly, id: "greetings" }])).toThrow(/Two modules are registered with the id "greetings"/);
  });
});

// What is really registered (modules/registry.ts), whatever modules there are by now.
describe("the module registry", () => {
  const navItems = MODULES.flatMap((module) => module.navItems);

  it("lists the hello module", () => {
    expect(MODULES.map((module) => module.id)).toContain("hello");
  });

  it("labels every navigation entry with a message id that exists, never with literal text", () => {
    const unknown = navItems.map((item) => item.labelId).filter((id) => !(id in messages.en));
    expect(unknown).toEqual([]);
  });

  it("leads every navigation entry to a route of its own module", () => {
    const lost = MODULES.flatMap((module) => {
      const paths = module.routes.map((route) => `/${route.path}`);
      return module.navItems.filter((item) => !paths.includes(item.to)).map((item) => `${module.id}: ${item.to}`);
    });
    expect(lost).toEqual([]);
  });

  it("has no module that still carries a placeholder permission of make new-module", () => {
    // The backend build refuses "todo." permissions too (tools/check-permissions.mjs): replace
    // them in the slice, the handler and the module.tsx with the codes of your guide.
    const placeholders = MODULES.flatMap((module) =>
      module.requiredPermissions.filter((permission) => permission.startsWith("todo.")).map((permission) => `${module.id}: ${permission}`)
    );
    expect(placeholders).toEqual([]);
  });
});

describe("the router after a login", () => {
  afterEach(cleanup);

  // The bug this guards against: after a login the browser comes back on "/?code=...", and the
  // login callback then restores the address the user had asked for with history.replaceState,
  // which no router hears. A router created when the file was loaded had read "/" and showed the
  // start page under the address /_design. So the router is created by a function, after login.
  it("reads the address when it is created, so the page asked for before the login is the page shown", async () => {
    const { createAppRouter } = await import("./router");
    window.history.replaceState({}, "", "/?code=abc&state=xyz");   // the browser returns from the login
    window.history.replaceState({}, "", "/_design");                // the callback restores the address

    const router = createAppRouter();                               // App creates it only now

    expect(router.state.location.pathname).toBe("/_design");
    router.dispose();
  });

  it("is not created as a side effect of loading the file", async () => {
    const module = await import("./router");

    expect(Object.keys(module).sort()).toEqual(["createAppRouter", "shellRoutes"]);
  });
});
