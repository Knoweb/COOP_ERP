// What the reporting module (M8) tells the shell about itself (17A section 7). It is listed once
// in ../registry.ts; the shell builds the router and the navigation from there.
//
// M8 demo scope (28A section 8): the dashboard with its tiles and trends, the exception queue, and the viewer of the
// catalogue's reports with CSV, A4 print and the run history. The
// definition editor, freshness and fleet, and analyst screens follow later.

import type { ReactElement } from "react";
import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { DashboardPage } from "./DashboardPage";
import { ReportPage } from "./ReportPage";
import { ExceptionsPage } from "./ExceptionList";
import { RequirePermission } from "../../shell/auth/RequirePermission";

// Every M8 screen reads APIs that need rpt.report.run; rpt.export.run alone (a custom role) must
// not get a page that ends in a 403. The CSV, print and run history inside ReportPage are shown
// to rpt.export.run only (M1A-07).
const REPORT = ["rpt.report.run"];
const guard = (page: ReactElement) => <RequirePermission anyOf={REPORT}>{page}</RequirePermission>;

export const reportingModule: ModuleDefinition = {
  id: "reporting",

  routes: [
    { path: "reporting", element: guard(<DashboardPage />) },
    { path: "reporting/exceptions", element: guard(<ExceptionsPage />) },
    { path: "reporting/reports/:reportId", element: guard(<ReportPage />) }
  ],

  // The label is a message id of reporting.messages.json, never literal text.
  navItems: [{ labelId: "reporting.nav", to: "/reporting", requiredPermissions: REPORT }],

  // x-permissions of openapi/m8reporting.yaml, read from the session's resolved set (PR #144).
  requiredPermissions: ["rpt.report.run", "rpt.export.run"]
};
