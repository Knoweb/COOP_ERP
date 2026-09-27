// What the trading module (M4) tells the shell about itself (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there, so a module
// never edits the shell.
//
// M4-11 (demo scope, doc 30 section 5.4; 24A section 8): the trading desk with the buyer's
// requisition book and the seller's order desk, the order card (submit, accept, reject), the
// delivery note (draft, issue, dispatch) and the goods received note (count, confirm). The other
// screens of 24A section 8 follow after the demo (docs/PLAN_TO_M2.md, "Deferred after the demo").

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { DeliveryNotePage } from "./DeliveryNotePage";
import { GrnPage } from "./GrnPage";
import { NewDeliveryNotePage } from "./NewDeliveryNotePage";
import { NewGrnPage } from "./NewGrnPage";
import { NewOrderPage } from "./NewOrderPage";
import { OrderPage } from "./OrderPage";
import { TradingPage } from "./TradingPage";

export const tradingModule: ModuleDefinition = {
  id: "trading",

  routes: [
    { path: "trading", element: <TradingPage /> },
    {
      path: "trading/orders/new",
      element: (
        <RequirePermission anyOf={["ord.order.draft"]}>
          <NewOrderPage />
        </RequirePermission>
      )
    },
    { path: "trading/orders/:orderId", element: <OrderPage /> },
    {
      path: "trading/delivery-notes/new",
      element: (
        <RequirePermission anyOf={["del.note.draft"]}>
          <NewDeliveryNotePage />
        </RequirePermission>
      )
    },
    { path: "trading/delivery-notes/:deliveryNoteId", element: <DeliveryNotePage /> },
    {
      path: "trading/grns/new",
      element: (
        <RequirePermission anyOf={["shop.grn.confirm"]}>
          <NewGrnPage />
        </RequirePermission>
      )
    },
    { path: "trading/grns/:grnId", element: <GrnPage /> }
  ],

  // The label is a message id of trading.messages.json, never literal text.
  navItems: [{ labelId: "trading.nav", to: "/trading" }],

  // x-permissions of openapi/m4trading.yaml, read from the session's resolved set (PR #144).
  requiredPermissions: [
    "trd.document.view",
    "ord.order.draft",
    "ord.order.submit",
    "ord.order.accept",
    "del.note.draft",
    "del.note.issue",
    "del.note.dispatch",
    "shop.grn.confirm"
  ]
};
