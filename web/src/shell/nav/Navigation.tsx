import { NavLink, useLocation } from "react-router-dom";
import { usePermissions } from "../auth/PermissionsContext";
import { useT } from "../i18n/useT";
import { navItemsFor } from "../modules/assemble";
import type { ModuleDefinition } from "../modules/ModuleDefinition";
import { NavIcon } from "./NavIcon";

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
  const location = useLocation();

  if (permissions === null) {
    // The set is on its way: an empty bar that says so, not one that claims "no permission".
    return (
      <nav className="shell-nav" aria-label={t("shell.nav.label").text} aria-busy="true">
        <p className="shell-nav__empty">{t("shell.permissions.loading").text}</p>
      </nav>
    );
  }

  const items = navItemsFor(modules, permissions);
  const currentPath = location.pathname.length > 1 && location.pathname.endsWith("/")
    ? location.pathname.slice(0, -1)
    : location.pathname;

  // Single active item by longest matching prefix so parent items like /catalogue or /reporting
  // do not stay simultaneously selected when visiting sub-routes like /catalogue/departments
  const activeItem = items
    .filter((item) => currentPath === item.to || currentPath.startsWith(item.to + "/"))
    .sort((a, b) => b.to.length - a.to.length)[0];

  return (
    <nav className="shell-nav" aria-label={t("shell.nav.label").text}>
      {items.length === 0 ? (
        // Say it, instead of an empty bar that looks like a page that failed to load.
        <p className="shell-nav__empty">{t(permissions.failed ? "shell.permissions.failed" : "shell.nav.empty").text}</p>
      ) : (
        <ul>
          {items.map((item) => (
            <li key={item.to}>
              {/* NavLink marks the current page with aria-current="page". */}
              <NavLink
                to={item.to}
                end={item.to !== activeItem?.to}
                title={t(item.labelId).text}
                aria-label={t(item.labelId).text}
              >
                <span className="nav-icon" aria-hidden="true">
                  <NavIcon to={item.to} />
                </span>
                <span className="nav-label">{t(item.labelId).text}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      )}
    </nav>
  );
}
