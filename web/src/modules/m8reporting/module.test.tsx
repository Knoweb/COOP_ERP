import { isValidElement } from "react";
import { describe, expect, it } from "vitest";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { reportingModule } from "./module";

describe("the reporting routes", () => {
  it("protect each route with the appropriate permission requirement", () => {
    expect(reportingModule.routes.length).toBe(6);
    for (const route of reportingModule.routes) {
      expect(isValidElement(route.element) && route.element.type === RequirePermission, String(route.path)).toBe(true);
      const perms = (route.element as { props: { anyOf: string[] } }).props.anyOf;
      if (route.path === "reporting/targets") {
        expect(perms).toEqual(["rpt.target.view"]);
      } else if (route.path === "reporting/profit-centers") {
        expect(perms).toEqual(["rpt.profit_center.view"]);
      } else {
        expect(perms).toEqual(["rpt.report.run"]);
      }
    }
  });

  it("show the menu entries only to users with the required permissions", () => {
    expect(reportingModule.navItems[0].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(reportingModule.navItems[1].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(reportingModule.navItems[2].requiredPermissions).toEqual(["rpt.profit_center.view"]);
    expect(reportingModule.navItems[3].requiredPermissions).toEqual(["rpt.target.view"]);
  });
});
