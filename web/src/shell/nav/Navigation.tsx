import { NavLink } from "react-router-dom";
import { usePermissions } from "../auth/PermissionsContext";
import { useT } from "../i18n/useT";
import { navItemsFor } from "../modules/assemble";
import type { ModuleDefinition } from "../modules/ModuleDefinition";

/**
 * The navigation of the back office: the entries of the modules this user may see, and no
 * others (doc 30 section 3). Which modules exist comes from web/src/modules/registry.ts;
 * which of them the user sees comes from the permission set the server resolved
 * (shell/auth/PermissionsContext.tsx, through shell/auth/permissions.ts). Nothing about a
 * particular module is written here.
 *
 * A hidden entry is a courtesy, not a lock: the route is guarded as well (shell/modules/
 * assemble.tsx), and the server decides in the end.
 */
export function Navigation({ modules }: { modules: ModuleDefinition[] }) {
  const permissions = usePermissions();
  const t = useT();

  if (permissions === null) {
    // The set is on its way: an empty bar that says so, not one that claims "no permission".
    return (
      <nav className="shell-nav" aria-label={t("shell.nav.label").text} aria-busy="true">
        <p className="shell-nav__empty">{t("shell.permissions.loading").text}</p>
      </nav>
    );
  }

  const items = navItemsFor(modules, permissions);

  return (
    <nav className="shell-nav" aria-label={t("shell.nav.label").text}>
      {items.length === 0 ? (
        // Say it, instead of an empty bar that looks like a page that failed to load.
        <p className="shell-nav__empty">{t(permissions.failed ? "shell.permissions.failed" : "shell.nav.empty").text}</p>
      ) : (
        <ul>
          {items.map((item) => {
            let Icon: JSX.Element;
            if (item.to.startsWith("/reporting")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="3" y1="9" x2="21" y2="9"></line><line x1="9" y1="21" x2="9" y2="9"></line></svg>;
            } else if (item.to.startsWith("/pricing")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="1" x2="12" y2="23"></line><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"></path></svg>;
            } else if (item.to.startsWith("/party/societies")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>;
            } else if (item.to.startsWith("/party/users")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>;
            } else if (item.to.startsWith("/party/roles")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>;
            } else if (item.to.startsWith("/party/grants")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"></path></svg>;
            } else if (item.to.startsWith("/party/relationships")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>;
            } else if (item.to.startsWith("/party")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>;
            } else if (item.to.startsWith("/catalogue")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path></svg>;
            } else if (item.to.startsWith("/inventory")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><line x1="16.5" y1="9.4" x2="7.5" y2="4.21"></line><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path><polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline><line x1="12" y1="22.08" x2="12" y2="12"></line></svg>;
            } else if (item.to.startsWith("/trading")) {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><circle cx="9" cy="21" r="1"></circle><circle cx="20" cy="21" r="1"></circle><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"></path></svg>;
            } else {
              Icon = <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>;
            }

            return (
              <li key={item.to}>
                {/* NavLink marks the entry of the current page with aria-current="page". */}
                <NavLink to={item.to}>
                  <span className="nav-icon">{Icon}</span>
                  <span className="nav-label">{t(item.labelId).text}</span>
                </NavLink>
              </li>
            );
          })}
        </ul>
      )}
    </nav>
  );
}
