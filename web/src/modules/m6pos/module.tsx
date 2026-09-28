// What the point of sale module (M6) tells the shell about itself (17A section 7). It is listed
// once in ../registry.ts; the shell builds the router and the navigation from there.
//
// Demo scope (docs/DEMO.md, phase 3): the receipts the shops' tills uploaded, one receipt, and
// the till sessions. Read-only: sales are made at the till and arrive through the sync contract.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { ReceiptPage } from "./ReceiptPage";
import { ReceiptsPage } from "./ReceiptsPage";
import { SessionsPage } from "./SessionsPage";

export const posModule: ModuleDefinition = {
  id: "pos",

  routes: [
    { path: "pos", element: <ReceiptsPage /> },
    { path: "pos/receipts/:documentId", element: <ReceiptPage /> },
    { path: "pos/sessions", element: <SessionsPage /> }
  ],

  // The label is a message id of pos.messages.json, never literal text.
  navItems: [{ labelId: "pos.nav", to: "/pos" }],

  // x-permissions of openapi/m6pos.yaml.
  requiredPermissions: ["pos.receipt.view"]
};
