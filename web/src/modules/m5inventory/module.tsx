// What the inventory module (M5) tells the shell about itself (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there.
//
// M5-12 (demo scope, doc 30 section 5.5): the stock position of a location and the opening
// balance (prepare, sign, countersign). The other screens of 25A section 8 follow after the demo.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { NewOpeningBalancePage } from "./NewOpeningBalancePage";
import { OpeningBalancePage } from "./OpeningBalancePage";
import { StockPage } from "./StockPage";

export const inventoryModule: ModuleDefinition = {
  id: "inventory",

  routes: [
    { path: "inventory", element: <StockPage /> },
    {
      path: "inventory/opening/new",
      element: (
        <RequirePermission anyOf={["inv.opening.prepare"]}>
          <NewOpeningBalancePage />
        </RequirePermission>
      )
    },
    { path: "inventory/opening/:openingBalanceId", element: <OpeningBalancePage /> }
  ],

  // The label is a message id of inventory.messages.json, never literal text.
  navItems: [{ labelId: "inventory.nav", to: "/inventory" }],

  // x-permissions of openapi/m5inventory.yaml, read from the session's resolved set (PR #144).
  requiredPermissions: ["inv.stock.view", "inv.opening.prepare", "inv.opening.sign", "inv.opening.countersign"]
};
