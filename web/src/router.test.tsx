import { cleanup, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
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
  return render(
    <IntlProvider locale={locale} messages={messages[locale]}>
      <RouterProvider router={router} />
    </IntlProvider>
  );
}

describe("the shell assembled from module definitions", () => {
  afterEach(cleanup);

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
