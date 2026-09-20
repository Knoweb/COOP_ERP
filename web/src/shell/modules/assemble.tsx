// Turns the list of module definitions into what the shell needs: the routes for the router
// and the entries for the navigation. router.tsx and Navigation.tsx call these two functions
// with the list of web/src/modules/registry.ts; the tests call them with lists of their own.

import type { RouteObject } from "react-router-dom";
import { hasAnyPermission } from "../auth/permissions";
import { RequirePermission } from "../auth/RequirePermission";
import type { Session } from "../auth/session";
import type { ModuleDefinition, NavItem } from "./ModuleDefinition";

/**
 * The routes of all modules, each one behind the permissions of its module. The guard is put
 * on here, once, so that a module cannot forget it: a module lists its screens and its
 * permissions, and a user without them gets the "not allowed" screen, never the page.
 */
export function routesOf(modules: ModuleDefinition[]): RouteObject[] {
  refuseDuplicateIds(modules);
  return modules.flatMap((module) =>
    module.routes.map((route) => ({
      ...route,
      element: <RequirePermission anyOf={module.requiredPermissions}>{route.element}</RequirePermission>
    }) as RouteObject)
  );
}

/** The navigation entries this user may see, in the order of the registry. */
export function navItemsFor(modules: ModuleDefinition[], session: Pick<Session, "roles">): NavItem[] {
  return modules
    .filter((module) => hasAnyPermission(session, module.requiredPermissions))
    .flatMap((module) => module.navItems);
}

/** Two modules with one id is a copy-and-paste mistake; say so at start-up, loudly. */
function refuseDuplicateIds(modules: ModuleDefinition[]) {
  const seen = new Set<string>();
  for (const module of modules) {
    if (seen.has(module.id)) {
      throw new Error(`Two modules are registered with the id "${module.id}" (web/src/modules/registry.ts)`);
    }
    seen.add(module.id);
  }
}
