// What the pricing module tells the shell about itself: its screens, its navigation entries and
// the permissions that open them (17A section 7). It is listed once in ../registry.ts; the
// shell builds the router and the navigation from there, so a module never edits the shell.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { PricingPage } from "./PricingPage";
import { TradePriceListPage } from "./TradePriceListPage";

export const pricingModule: ModuleDefinition = {
  id: "pricing",

  // doc 30 section 5.3, "Trade price list": the lists, then one version with its lines.
  routes: [
    { path: "pricing", element: <PricingPage /> },
    { path: "pricing/lists/:listId", element: <TradePriceListPage /> }
  ],

  // The label is a message id of pricing.messages.json, never literal text.
  navItems: [{ labelId: "pricing.nav", to: "/pricing" }],

  // x-permissions of openapi/m3pricing.yaml, read from the session's resolved set (PR #144). A
  // user with one of them sees the module; the pages hide what the user may not do.
  requiredPermissions: ["prc.pricelist.view", "prc.pricelist.author"]
};
