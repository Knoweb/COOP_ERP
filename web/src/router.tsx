// The router of the back office. It knows no module by name: the modules are listed in
// modules/registry.ts (that is where `make new-module` adds its line), and the shell turns
// that list into routes and navigation (17A section 7).

import { createBrowserRouter, Outlet } from "react-router-dom";
import type { RouteObject } from "react-router-dom";
import { MODULES } from "./modules/registry";
import { UserMenu } from "./shell/auth/UserMenu";
import { useT } from "./shell/i18n/useT";
import { routesOf } from "./shell/modules/assemble";
import type { ModuleDefinition } from "./shell/modules/ModuleDefinition";
import { Navigation } from "./shell/nav/Navigation";
import { ScopeBanner } from "./shell/scope/ScopeBanner";
import { ScopeProvider } from "./shell/scope/ScopeContext";

/** The frame around every page: the scope banner and the user on top, then the navigation, then the page. */
function RootLayout({ modules }: { modules: ModuleDefinition[] }) {
  return (
    <ScopeProvider>
      <header className="shell-header">
        <ScopeBanner />
        <UserMenu />
      </header>
      <Navigation modules={modules} />
      <Outlet />
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
        // Last: an address no module owns gets a sentence, not a blank page.
        { path: "*", element: <ShellMessage titleId="shell.not_found.title" textId="shell.not_found.text" /> }
      ]
    }
  ];
}

export const router = createBrowserRouter(shellRoutes(MODULES));
