import { isValidElement } from "react";
import { describe, expect, it } from "vitest";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { navItemsFor } from "../../shell/modules/assemble";
import { reportingModule } from "./module";

describe("the reporting routes", () => {
  it("protect each route with the appropriate permission requirement", () => {
    expect(reportingModule.routes.length).toBe(9);
    for (const route of reportingModule.routes) {
      expect(isValidElement(route.element) && route.element.type === RequirePermission, String(route.path)).toBe(true);
      const perms = (route.element as { props: { anyOf: string[] } }).props.anyOf;
      if (route.path === "reporting/targets" || route.path === "reporting/revenue-estimator") {
        expect(perms).toEqual(["rpt.target.view"]);
      } else if (route.path === "reporting/profit-centers") {
        expect(perms).toEqual(["rpt.profit_center.view"]);
      } else if (route.path === "reporting/assets") {
        expect(perms).toEqual(["rpt.asset.view"]);
      } else {
        expect(perms).toEqual(["rpt.report.run"]);
      }
    }
  });

  it("show the menu entries only to users with the required permissions", () => {
    expect(reportingModule.navItems.length).toBe(7);
    expect(reportingModule.navItems[0].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(reportingModule.navItems[1].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(reportingModule.navItems[2].requiredPermissions).toEqual(["rpt.profit_center.view"]);
    expect(reportingModule.navItems[3].requiredPermissions).toEqual(["rpt.asset.view"]);
    expect(reportingModule.navItems[4].requiredPermissions).toEqual(["rpt.target.view"]);
    expect(reportingModule.navItems[5].requiredPermissions).toEqual(["rpt.target.view"]);
    expect(reportingModule.navItems[6].requiredPermissions).toEqual(["rpt.report.run"]);
  });

  it("shows all reporting navigation entries to a FEDERATION_VIEW user", () => {
    const fedView = {
      policyClass: "FEDERATION_VIEW" as const,
      permissions: [
        "rpt.report.run",
        "rpt.export.run",
        "rpt.target.view",
        "rpt.profit_center.view",
        "rpt.asset.view"
      ]
    };
    const items = navItemsFor([reportingModule], fedView);
    expect(items.map((i) => i.to)).toEqual([
      "/reporting",
      "/reporting/shop-reports",
      "/reporting/profit-centers",
      "/reporting/assets",
      "/reporting/targets",
      "/reporting/revenue-estimator",
      "/reporting/finance"
    ]);
  });
});
