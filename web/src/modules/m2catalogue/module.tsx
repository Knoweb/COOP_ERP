// What the catalogue module (M2) tells the shell about itself (17A section 7). It is listed
// once in ../registry.ts; the shell builds the router and the navigation from there, so a
// module never edits the shell.
//
// M2-10 (demo scope, doc 30 section 5.2): the catalogue browser and the SKU view and editor.
// Bulk import, promote and merge and the control price register follow after the demo.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { CataloguePage } from "./CataloguePage";
import { NewSkuPage } from "./NewSkuPage";
import { SkuPage } from "./SkuPage";

export const catalogueModule: ModuleDefinition = {
  id: "catalogue",

  routes: [
    { path: "catalogue", element: <CataloguePage /> },
    {
      path: "catalogue/skus/new",
      element: (
        <RequirePermission anyOf={["cat.sku.create_local"]}>
          <NewSkuPage />
        </RequirePermission>
      )
    },
    { path: "catalogue/skus/:skuId", element: <SkuPage /> }
  ],

  // The label is a message id of catalogue.messages.json, never literal text.
  navItems: [{ labelId: "catalogue.nav", to: "/catalogue" }],

  // x-permissions of openapi/m2catalogue.yaml, read from the session's resolved set (PR #144).
  requiredPermissions: ["cat.sku.view", "cat.sku.create_local", "cat.sku.create", "cat.barcode.manage"]
};
