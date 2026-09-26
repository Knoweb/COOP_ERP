// What the party module (M1: the society register) tells the shell about itself: its screens,
// its navigation entries and the permissions that open them (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there, so a module
// never edits the shell.

import { RequirePermission } from "../../shell/auth/RequirePermission";
import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { BulkRegisterPage } from "./BulkRegisterPage";
import { RegisterSocietyPage } from "./RegisterSocietyPage";
import { SocietyCardPage } from "./SocietyCardPage";
import { SocietyRegisterPage } from "./SocietyRegisterPage";

export const partyModule: ModuleDefinition = {
  id: "party",

  // The two register forms need the permission to register, not only one of the module's:
  // a user who may only view the register (the Federation view) sees the page that says so
  // instead of a form the server would refuse.
  routes: [
    { path: "party/societies", element: <SocietyRegisterPage /> },
    {
      path: "party/societies/new",
      element: (
        <RequirePermission anyOf={["gov.entity.register"]}>
          <RegisterSocietyPage />
        </RequirePermission>
      )
    },
    {
      path: "party/societies/bulk",
      element: (
        <RequirePermission anyOf={["gov.entity.register"]}>
          <BulkRegisterPage />
        </RequirePermission>
      )
    },
    { path: "party/societies/:entityId", element: <SocietyCardPage /> }
  ],

  // The label is a message id of party.messages.json, never literal text.
  navItems: [{ labelId: "party.nav", to: "/party/societies" }],

  // The same codes as the x-permission of openapi/m1party.yaml. A user with at least one of
  // them sees the module; each page hides what the user may not do.
  requiredPermissions: ["gov.entity.view", "gov.entity.register", "gov.entity.activate", "gov.entity.suspend"]
};
