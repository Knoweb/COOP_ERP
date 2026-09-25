// What the catalogue module (M2) tells the shell about itself (17A section 7). It is listed
// once in ../registry.ts; the shell builds the router and the navigation from there, so a
// module never edits the shell.
//
// M2-01 registers the module and nothing else: it has no screen yet, so no route and no
// navigation entry, and therefore no permission to name (requiredPermissions must be
// x-permissions of openapi/m2catalogue.yaml, which has no operation yet). The eight screens of
// 22A section 8 arrive with M2-10, and with them the cat.* codes that open the module.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";

export const catalogueModule: ModuleDefinition = {
  id: "catalogue",
  routes: [],
  navItems: [],
  requiredPermissions: []
};
