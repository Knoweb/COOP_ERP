// Turns the list of module definitions into what the shell needs: the routes for the router
// and the entries for the navigation. router.tsx and Navigation.tsx call these two functions
// with the list of web/src/modules/registry.ts; the tests call them with lists of their own.

import { Outlet } from "react-router-dom";
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
  return modules.flatMap((module) => module.routes.map((route) => guarded(route, module)));
}

/**
 * One top-level route of a module behind the module's permissions. The guard sits on the top
 * route only: the routes under it are rendered inside it, so they are behind it too.
 *
 * A route can say what it shows in three ways, and the guard has to keep each working:
 *   - `element`: the usual one;
 *   - `Component`: the same thing, as a component and not as an element;
 *   - neither: a parent that only groups its children (/party/entities, /party/locations).
 *     It shows the matching child, which is what <Outlet /> does. Without it the guard would
 *     have nothing inside and every screen of the module would come out blank.
 */
function guarded(route: RouteObject, module: ModuleDefinition): RouteObject {
  if (route.lazy) {
    // A lazily loaded route brings its element later, past this guard. Not needed yet; when it
    // is, guard inside the lazy function. Better a clear stop now than an open page then.
    throw new Error(`Module "${module.id}": a top-level route uses "lazy", which the permission guard does not cover yet`);
  }
  const { Component, element, ...rest } = route;
  const page = Component ? <Component /> : (element ?? <Outlet />);
  return {
    ...rest,
    element: <RequirePermission anyOf={module.requiredPermissions}>{page}</RequirePermission>
  } as RouteObject;
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
