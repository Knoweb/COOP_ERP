// The list of the modules of the web client. This is the ONE place a module is registered:
// one import line and one entry line, and `make new-module` writes both. The shell reads the
// list (router.tsx for the routes, shell/nav/Navigation.tsx for the navigation); a module
// developer never edits the shell (17A section 7).
//
// The order of this list is the order of the navigation.

import type { ModuleDefinition } from "../shell/modules/ModuleDefinition";
import { helloModule } from "./hello/module";
import { pricingModule } from "./m3pricing/module";
// new-module:import (make new-module adds a line above this one; keep the comment)

export const MODULES: ModuleDefinition[] = [
  helloModule,
  pricingModule,
  // new-module:entry (make new-module adds a line above this one; keep the comment)
];
