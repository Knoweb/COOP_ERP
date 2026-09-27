// What the trading module (M4) tells the shell about itself (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there, so a module
// never edits the shell.
//
// M4-01 registers the module and nothing else: it has no screen yet, so no route and no
// navigation entry, and therefore no permission to name (requiredPermissions must be
// x-permissions of openapi/m4trading.yaml, which has no operation yet). The screens of 24A
// section 8 arrive with M4-11.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";

export const tradingModule: ModuleDefinition = {
  id: "trading",
  routes: [],
  navItems: [],
  requiredPermissions: []
};
