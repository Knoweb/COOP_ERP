import { NavLink } from "react-router-dom";
import { useSession } from "../auth/session";
import { useT } from "../i18n/useT";
import { navItemsFor } from "../modules/assemble";
import type { ModuleDefinition } from "../modules/ModuleDefinition";

/**
 * The navigation of the back office: the entries of the modules this user may see, and no
 * others (doc 30 section 3). Which modules exist comes from web/src/modules/registry.ts;
 * which of them the user sees comes from shell/auth/permissions.ts. Nothing about a
 * particular module is written here.
 *
 * A hidden entry is a courtesy, not a lock: the route is guarded as well (shell/modules/
 * assemble.tsx), and the server decides in the end.
 */
export function Navigation({ modules }: { modules: ModuleDefinition[] }) {
  const session = useSession();
  const t = useT();

  const items = session ? navItemsFor(modules, session) : [];

  return (
    <nav className="shell-nav" aria-label={t("shell.nav.label").text}>
      {items.length === 0 ? (
        // Say it, instead of an empty bar that looks like a page that failed to load.
        <p className="shell-nav__empty">{t("shell.nav.empty").text}</p>
      ) : (
        <ul>
          {items.map((item) => (
            <li key={item.to}>
              {/* NavLink marks the entry of the current page with aria-current="page". */}
              <NavLink to={item.to}>{t(item.labelId).text}</NavLink>
            </li>
          ))}
        </ul>
      )}
    </nav>
  );
}
