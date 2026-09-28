// What the party module (M1: the society register and the administration of users, roles and
// external grants) tells the shell about itself: its screens, its navigation entries and the
// permissions that open them (17A section 7). It is listed once in ../registry.ts; the shell
// builds the router and the navigation from there, so a module never edits the shell.

import type { ReactElement } from "react";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { BulkRegisterPage } from "./BulkRegisterPage";
import { GrantsPage } from "./GrantsPage";
import { NewUserPage } from "./NewUserPage";
import { RegisterSocietyPage } from "./RegisterSocietyPage";
import { RolesPage } from "./RolesPage";
import { SocietyCardPage } from "./SocietyCardPage";
import { SocietyRegisterPage } from "./SocietyRegisterPage";
import { UserCardPage } from "./UserCardPage";
import { UsersPage } from "./UsersPage";

/**
 * The permissions of each group of screens: the same codes as the x-permission of
 * openapi/m1party.yaml. Each navigation entry and each route uses its group, so that an entry
 * is shown exactly to the users its page opens for (docs/demo/08: hidden, not inert).
 */
export const SOCIETY_PERMISSIONS = ["gov.entity.view", "gov.entity.register", "gov.entity.activate", "gov.entity.suspend"];
export const USER_PERMISSIONS = ["gov.user.view", "gov.user.manage"];
export const ROLE_PERMISSIONS = ["gov.role.manage"];
export const GRANT_PERMISSIONS = ["gov.external.grant"];

const guard = (anyOf: string[], page: ReactElement) => <RequirePermission anyOf={anyOf}>{page}</RequirePermission>;

export const partyModule: ModuleDefinition = {
  id: "party",

  // The two register forms need the permission to register, not only one of the module's:
  // a user who may only view the register (the Federation view) sees the page that says so
  // instead of a form the server would refuse. The same for "New user".
  routes: [
    { path: "party/societies", element: guard(SOCIETY_PERMISSIONS, <SocietyRegisterPage />) },
    { path: "party/societies/new", element: guard(["gov.entity.register"], <RegisterSocietyPage />) },
    { path: "party/societies/bulk", element: guard(["gov.entity.register"], <BulkRegisterPage />) },
    { path: "party/societies/:entityId", element: guard(SOCIETY_PERMISSIONS, <SocietyCardPage />) },
    { path: "party/users", element: guard(USER_PERMISSIONS, <UsersPage />) },
    { path: "party/users/new", element: guard(["gov.user.manage"], <NewUserPage />) },
    { path: "party/users/:userId", element: guard(USER_PERMISSIONS, <UserCardPage />) },
    { path: "party/roles", element: guard(ROLE_PERMISSIONS, <RolesPage />) },
    { path: "party/grants", element: guard(GRANT_PERMISSIONS, <GrantsPage />) }
  ],

  // The labels are message ids of party.messages.json, never literal text.
  navItems: [
    { labelId: "party.nav", to: "/party/societies", requiredPermissions: SOCIETY_PERMISSIONS },
    { labelId: "party.nav.users", to: "/party/users", requiredPermissions: USER_PERMISSIONS },
    { labelId: "party.nav.roles", to: "/party/roles", requiredPermissions: ROLE_PERMISSIONS },
    { labelId: "party.nav.grants", to: "/party/grants", requiredPermissions: GRANT_PERMISSIONS }
  ],

  // Every code any screen of the module uses: a user with at least one sees the module; each
  // entry and page then narrows to its own group.
  requiredPermissions: [...SOCIETY_PERMISSIONS, ...USER_PERMISSIONS, ...ROLE_PERMISSIONS, ...GRANT_PERMISSIONS]
};
