// The router of the back office. It knows no module by name: the modules are listed in
// modules/registry.ts (that is where `make new-module` adds its line), and the shell turns
// that list into routes and navigation (17A section 7).

import { useState, useEffect } from "react";
import { createBrowserRouter, Outlet, useLocation } from "react-router-dom";
import type { RouteObject } from "react-router-dom";
import { MODULES } from "./modules/registry";
import { StepUpReplay } from "./shell/auth/StepUpReplay";
import { UserMenu } from "./shell/auth/UserMenu";
import { isTrainingMode, TrainingBadge } from "./shell/components/TrainingBadge";
import { DesignPage } from "./shell/design/DesignPage";
import { useT } from "./shell/i18n/useT";
import { routesOf } from "./shell/modules/assemble";
import type { ModuleDefinition } from "./shell/modules/ModuleDefinition";
import { Navigation } from "./shell/nav/Navigation";
import { ScopeBanner } from "./shell/scope/ScopeBanner";
import { ScopeProvider } from "./shell/scope/ScopeContext";

import coopLogo from "./shell/logo.jpg";

import { useSession } from "./shell/auth/session";

function SignOutSidebar() {
  const session = useSession();
  const t = useT();
  if (!session) return null;
  return (
        <button
          type="button"
          className="btn-signout"
          onClick={session.signOut}
          title={t("shell.auth.sign_out").text}
          aria-label={t("shell.auth.sign_out").text}
        >
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
        <polyline points="16 17 21 12 16 7"></polyline>
        <line x1="21" y1="12" x2="9" y2="12"></line>
      </svg>
      <span className="signout-label">
        {t("shell.auth.sign_out").text}
      </span>
    </button>
  );
}

/**
 * The frame around every page: the scope banner and the user on top, then the navigation, then
 * the page. In training mode the frame is loud (doc 30 section 2.3): a band above everything and
 * a coloured border around the whole page, so that practice is never mistaken for real work.
 */
function RootLayout({ modules }: { modules: ModuleDefinition[] }) {
  // Temporary source: a build flag. The real one is a later kernel/M6 concern (TrainingBadge.tsx).
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try {
      return localStorage.getItem("coop-sidebar-collapsed") === "true";
    } catch {
      return false;
    }
  });
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const location = useLocation();
  const t = useT();

  useEffect(() => {
    setMobileNavOpen(false);
  }, [location.pathname]);

  const toggleSidebar = () => {
    setSidebarCollapsed((current) => {
      const next = !current;

      try {
        localStorage.setItem("coop-sidebar-collapsed", String(next));
      } catch {
        // Ignore storage errors
      }

      return next;
    });
  };

  const closeMobileNav = () => {
    setMobileNavOpen(false);
  };

  const training = isTrainingMode();
  return (
    <ScopeProvider>
      <div className={training ? "shell shell--training" : "shell"}>
        <TrainingBadge active={training} />
        <div
          className={`shell-layout${sidebarCollapsed ? " shell-layout--collapsed" : ""}${mobileNavOpen ? " shell-layout--mobile-open" : ""}`}
        >
          {mobileNavOpen && (
            <div
              className="shell-sidebar-backdrop"
              onClick={closeMobileNav}
              aria-hidden="true"
            />
          )}
          <aside className={`shell-sidebar${mobileNavOpen ? " shell-sidebar--open" : ""}`}>
            <div className="shell-sidebar-brand">
              <span className="brand-logo">
                <img src={coopLogo} alt="" aria-hidden="true" />
              </span>
              <span className="brand-text">COOPFED ERP</span>
              <button
                type="button"
                className="shell-sidebar-toggle"
                onClick={() => {
                  if (mobileNavOpen) {
                    closeMobileNav();
                  } else {
                    toggleSidebar();
                  }
                }}
                aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
                aria-expanded={!sidebarCollapsed}
                aria-controls="coop-sidebar-navigation"
                title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
              >
                <svg
                  viewBox="0 0 24 24"
                  width="20"
                  height="20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <rect x="3" y="4" width="18" height="16" rx="2" />
                  <line x1="9" y1="4" x2="9" y2="20" />
                  <path d={sidebarCollapsed ? "m13 9 3 3-3 3" : "m16 9-3 3 3 3"} />
                </svg>
              </button>
            </div>
            <div id="coop-sidebar-navigation" className="shell-sidebar-nav">
              <Navigation modules={modules} />
            </div>
            <div className="shell-sidebar-footer">
              <SignOutSidebar />
            </div>
          </aside>
          <div className="shell-main">
            <header className="shell-topbar">
              <div className="shell-topbar-left">
                <button
                  type="button"
                  className="shell-mobile-toggle"
                  onClick={() => setMobileNavOpen((open) => !open)}
                  aria-label={t("shell.nav.label").text}
                  aria-expanded={mobileNavOpen}
                  aria-controls="coop-sidebar-navigation"
                  title={t("shell.nav.label").text}
                >
                  <svg
                    viewBox="0 0 24 24"
                    width="22"
                    height="22"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <line x1="3" y1="12" x2="21" y2="12" />
                    <line x1="3" y1="6" x2="21" y2="6" />
                    <line x1="3" y1="18" x2="21" y2="18" />
                  </svg>
                </button>
                <ScopeBanner />
              </div>
              <div className="shell-topbar-right">
                <UserMenu />
              </div>
            </header>
            <StepUpReplay />
            <div className="shell-content-container">
              <Outlet />
            </div>
          </div>
        </div>
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
