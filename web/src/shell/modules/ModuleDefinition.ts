// What a module tells the shell about itself (17A section 7). A module exports ONE object of
// this type from its `module.tsx`, and is listed once in web/src/modules/registry.ts. The
// shell builds the router and the navigation from those objects, so a module developer adds
// a folder and a line and never edits the shell.

import type { RouteObject } from "react-router-dom";

/** One entry of the navigation. */
export type NavItem = {
  /**
   * The message id of the label, from the module's <module>.messages.json. Never literal
   * text: the navigation is read in Sinhala, Tamil and English like everything else.
   */
  labelId: string;
  /** Where the entry leads: an absolute path of one of the module's routes, for example "/hello". */
  to: string;
};

export type ModuleDefinition = {
  /** A short name no other module has, for example "hello". Used as a key, never shown. */
  id: string;

  /** The screens of the module, mounted under "/". Paths are relative: "hello", "hello/:id". */
  routes: RouteObject[];

  /** The entries the module adds to the navigation. A module with no screen of its own has none. */
  navItems: NavItem[];

  /**
   * The permission codes that open this module: a user who holds AT LEAST ONE of them sees
   * the navigation entries and may open the routes; a user who holds none sees neither, and
   * gets the "not allowed" screen when the address is typed by hand. They are the same codes
   * as the `x-permission` of the module's OpenAPI slice; list every one the screens use.
   *
   * An empty list means every signed-in user. That is right for almost nothing, so think twice.
   *
   * One screen needs a stricter rule than the module? Wrap that screen's element in
   * <RequirePermission anyOf={[...]}> yourself (shell/auth/RequirePermission.tsx).
   *
   * This decides what is SHOWN. What is ALLOWED is decided by the server on every request
   * (doc 30 section 3): a screen must still handle a 403 answer.
   */
  requiredPermissions: string[];
};
