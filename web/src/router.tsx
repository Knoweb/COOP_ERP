// The router of the back office. It knows no module by name: the modules are listed in
// modules/registry.ts (that is where `make new-module` adds its line), and the shell turns
// that list into routes and navigation (17A section 7).

import { createBrowserRouter, Outlet } from "react-router-dom";
import type { RouteObject } from "react-router-dom";
import { MODULES } from "./modules/registry";
import { UserMenu } from "./shell/auth/UserMenu";
import { isTrainingMode, TrainingBadge } from "./shell/components/TrainingBadge";
import { DesignPage } from "./shell/design/DesignPage";
import { useT } from "./shell/i18n/useT";
import { routesOf } from "./shell/modules/assemble";
import type { ModuleDefinition } from "./shell/modules/ModuleDefinition";
import { Navigation } from "./shell/nav/Navigation";
import { ScopeBanner } from "./shell/scope/ScopeBanner";
import { ScopeProvider } from "./shell/scope/ScopeContext";

/**
 * The frame around every page: the scope banner and the user on top, then the navigation, then
 * the page. In training mode the frame is loud (doc 30 section 2.3): a band above everything and
 * a coloured border around the whole page, so that practice is never mistaken for real work.
 */
function RootLayout({ modules }: { modules: ModuleDefinition[] }) {
  // Temporary source: a build flag. The real one is a later kernel/M6 concern (TrainingBadge.tsx).
  const training = isTrainingMode();
  return (
    <ScopeProvider>
      <div className={training ? "shell shell--training" : "shell"}>
        <TrainingBadge active={training} />
        <header className="shell-header">
          <ScopeBanner />
          <UserMenu />
        </header>
        <Navigation modules={modules} />
        <Outlet />
      </div>
    </ScopeProvider>
  );
}

/** A short text in the place of a page: the start page, and an address that leads nowhere. */
function ShellMessage({ titleId, textId }: { titleId: string; textId: string }) {
  const t = useT();
  return (
    <main className="shell-message">
      <h1>{t(titleId).text}</h1>
      <p>{t(textId).text}</p>
    </main>
  );
}

/**
 * All routes of the application for a list of modules. A function, and not only the constant
 * below, so that a test can build the application with modules of its own.
 */
export function shellRoutes(modules: ModuleDefinition[]): RouteObject[] {
  return [
    {
      path: "/",
      element: <RootLayout modules={modules} />,
      children: [
        { index: true, element: <ShellMessage titleId="shell.home.title" textId="shell.home.text" /> },
        ...routesOf(modules),
        // The design reference: a page of the shell, not a module. It is in no navigation, shows
        // no data and calls no API, so it needs no permission; like everything here it is behind
        // the login (App.tsx). The underscore keeps the address out of the way of module names.
        { path: "_design", element: <DesignPage /> },
        // Last: an address no module owns gets a sentence, not a blank page.
        { path: "*", element: <ShellMessage titleId="shell.not_found.title" textId="shell.not_found.text" /> }
      ]
    }
  ];
}

/**
 * The router of the application. A FUNCTION, called once by App after login has finished, and
 * deliberately not a constant created when this file is loaded.
 *
 * Why: a router reads the address once, when it is created. After a login the browser comes
 * back on "/?code=...&state=...", and only then does the login callback put the address the
 * user had asked for back (shell/auth/oidc.ts). A router created at load time had already read
 * "/", never heard of the change, and showed the start page under the address /_design.
 */
export function createAppRouter() {
  return createBrowserRouter(shellRoutes(MODULES));
}
