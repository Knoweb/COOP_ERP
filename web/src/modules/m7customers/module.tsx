// What the customers module (M7, back office) tells the shell about itself (17A section 7): the
// society's member register, the customer card with its credit account and repayment, and the
// statement. It is listed once in ../registry.ts; the shell builds the router and the navigation
// from there, so a module never edits the shell. The till's screens (lookup, account tender,
// repayment at the till) come with the till (CR-30-1).

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { CustomerCardPage } from "./CustomerCardPage";
import { CustomersPage } from "./CustomersPage";
import { StatementPage } from "./StatementPage";

export const customersModule: ModuleDefinition = {
  id: "customers",

  routes: [
    { path: "customers", element: <CustomersPage /> },
    { path: "customers/:customerId", element: <CustomerCardPage /> },
    { path: "customers/:customerId/accounts/:accountId/statement", element: <StatementPage /> }
  ],

  // The label is a message id of customers.messages.json, never literal text.
  navItems: [{ labelId: "customers.nav", to: "/customers" }],

  // x-permissions of openapi/m7customers.yaml. A user with at least one of them sees the module;
  // the screens hide what the user may not do.
  requiredPermissions: [
    "cus.customer.view",
    "cus.customer.register",
    "cus.customer.manage",
    "cus.account.manage",
    "cus.payment.record"
  ]
};
