// What the party module (M1: the society register) tells the shell about itself: its screens,
// its navigation entries and the permissions that open them (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there, so a module
// never edits the shell.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { BulkRegisterPage } from "./BulkRegisterPage";
import { RegisterSocietyPage } from "./RegisterSocietyPage";
import { SocietyCardPage } from "./SocietyCardPage";
import { SocietyRegisterPage } from "./SocietyRegisterPage";

export const partyModule: ModuleDefinition = {
  id: "party",

  routes: [
    { path: "party/societies", element: <SocietyRegisterPage /> },
    { path: "party/societies/new", element: <RegisterSocietyPage /> },
    { path: "party/societies/bulk", element: <BulkRegisterPage /> },
    { path: "party/societies/:entityId", element: <SocietyCardPage /> }
  ],

  // The label is a message id of party.messages.json, never literal text.
  navItems: [{ labelId: "party.nav", to: "/party/societies" }],

  // The same codes as the x-permission of openapi/m1party.yaml. A user with at least one of
  // them sees the module; each page hides what the user may not do.
  requiredPermissions: ["gov.entity.view", "gov.entity.register", "gov.entity.activate", "gov.entity.suspend"]
};
