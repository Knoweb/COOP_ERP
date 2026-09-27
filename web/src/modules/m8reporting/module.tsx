// What the reporting module (M8) tells the shell about itself (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there.
//
// M8 demo scope (28A section 8): the dashboard with its tiles, and the viewer of the three
// reports (stock position, trade by distributor, invoices issued) with CSV and A4 print. The
// catalogue editor, exception queue, freshness and fleet, and analyst screens follow later.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { DashboardPage } from "./DashboardPage";
import { ReportPage } from "./ReportPage";

export const reportingModule: ModuleDefinition = {
  id: "reporting",

  routes: [
    { path: "reporting", element: <DashboardPage /> },
    { path: "reporting/reports/:reportId", element: <ReportPage /> }
  ],

  // The label is a message id of reporting.messages.json, never literal text.
  navItems: [{ labelId: "reporting.nav", to: "/reporting" }],

  // x-permissions of openapi/m8reporting.yaml, read from the session's resolved set (PR #144).
  requiredPermissions: ["rpt.report.run", "rpt.export.run"]
};
