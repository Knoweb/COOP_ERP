// What the integration module (M9) tells the shell about itself (17A section 7): the journal
// exports for the accounting package and the notification screens (rules, templates, the delivery
// log). It is listed once in ../registry.ts; the shell builds the router and the navigation from
// there. The API clients, providers, e-invoicing and API documentation screens of 29A section 8
// come with their tickets.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { JournalExportsPage } from "./JournalExportsPage";
import { NotificationsPage } from "./NotificationsPage";

const JOURNAL = ["int.journal.read", "int.journal.export"];
const NOTIFY = ["int.notify.view", "int.notify.manage"];

export const integrationModule: ModuleDefinition = {
  id: "integration",

  routes: [
    {
      path: "integration/journal",
      element: (
        <RequirePermission anyOf={JOURNAL}>
          <JournalExportsPage />
        </RequirePermission>
      )
    },
    {
      path: "integration/notifications",
      element: (
        <RequirePermission anyOf={NOTIFY}>
          <NotificationsPage />
        </RequirePermission>
      )
    }
  ],

  // The labels are message ids of integration.messages.json, never literal text.
  navItems: [
    { labelId: "integration.nav.journal", to: "/integration/journal", requiredPermissions: JOURNAL },
    { labelId: "integration.nav.notifications", to: "/integration/notifications", requiredPermissions: NOTIFY }
  ],

  // x-permissions of openapi/m9integration.yaml. A user with at least one of them sees the module;
  // each entry and each screen asks for its own.
  requiredPermissions: [...JOURNAL, ...NOTIFY]
};
