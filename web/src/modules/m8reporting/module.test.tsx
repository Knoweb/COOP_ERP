import { isValidElement } from "react";
import { describe, expect, it } from "vitest";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { reportingModule } from "./module";

// M1A-07: a user with only rpt.export.run (a custom role) used to get the menu entry and a 403 page.
describe("the reporting routes", () => {
  it("each sit behind rpt.report.run, which every screen's API needs", () => {
    expect(reportingModule.routes.length).toBe(3);
    for (const route of reportingModule.routes) {
      expect(isValidElement(route.element) && route.element.type === RequirePermission, String(route.path)).toBe(true);
      expect((route.element as { props: { anyOf: string[] } }).props.anyOf).toEqual(["rpt.report.run"]);
    }
  });

  it("show the menu entry only to a user who may run reports", () => {
    expect(reportingModule.navItems[0].requiredPermissions).toEqual(["rpt.report.run"]);
  });
});
