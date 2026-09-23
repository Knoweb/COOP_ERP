// What the pricing module tells the shell about itself: its screens, its navigation entries and
// the permissions that open them (17A section 7). It is listed once in ../registry.ts; the
// shell builds the router and the navigation from there, so a module never edits the shell.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { PricingPage } from "./PricingPage";

export const pricingModule: ModuleDefinition = {
  id: "pricing",

  routes: [
    {
      path: "pricing",
      element: <PricingPage />
    }
  ],

  // The label is a message id of pricing.messages.json, never literal text.
  navItems: [{ labelId: "pricing.nav", to: "/pricing" }],

  // The same codes as the x-permission of openapi/m3pricing.yaml. A user with at least one of
  // them sees the module; the page itself hides what the user may not do (PricingPage.tsx).
  requiredPermissions: ["prc.price_list.read", "prc.price_list.register"]
};
