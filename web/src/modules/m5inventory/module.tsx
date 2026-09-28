// What the inventory module (M5) tells the shell about itself (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there.
//
// M5-12 (demo scope, doc 30 section 5.5): the stock position of a location and the opening
// balance (prepare, sign, countersign). The other screens of 25A section 8 follow after the demo.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { NewOpeningBalancePage } from "./NewOpeningBalancePage";
import { OpeningBalancePage } from "./OpeningBalancePage";
import { StockCardPage } from "./StockCardPage";
import { StockPage } from "./StockPage";
import { TransfersPage } from "./TransfersPage";
import { CountsPage } from "./CountsPage";
import { CountPage } from "./CountPage";
import { WriteOffsPage } from "./WriteOffsPage";
import { WriteOffPage } from "./WriteOffPage";
import { RepacksPage } from "./RepacksPage";

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
    { path: "inventory/opening/:openingBalanceId", element: <OpeningBalancePage /> },
    // The stock card of an item at a location, linked from the stock position (25A section 8).
    { path: "inventory/locations/:locationId/skus/:skuId", element: <StockCardPage /> },
    // M5-09 (demo scope): send stock to another location of the entity, receive it there.
    { path: "inventory/transfers", element: <TransfersPage /> },
    // M5-11, M5-13 and the repack (25A section 8, back office): counts with their adjustment,
    // the damage and expiry register, repacks.
    { path: "inventory/counts", element: <CountsPage /> },
    { path: "inventory/counts/:taskId", element: <CountPage /> },
    { path: "inventory/write-offs", element: <WriteOffsPage /> },
    { path: "inventory/write-offs/:writeOffId", element: <WriteOffPage /> },
    { path: "inventory/repacks", element: <RepacksPage /> }
  ],

  // The label is a message id of inventory.messages.json, never literal text.
  navItems: [{ labelId: "inventory.nav", to: "/inventory" }],

  // x-permissions of openapi/m5inventory.yaml, read from the session's resolved set (PR #144).
  requiredPermissions: [
    "inv.stock.view",
    "inv.opening.prepare",
    "inv.opening.sign",
    "inv.opening.countersign",
    "inv.transfer.issue",
    "shop.transfer.receive"
  ]
};
