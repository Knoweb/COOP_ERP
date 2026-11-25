import { isValidElement } from "react";
import { describe, expect, it } from "vitest";
import { RequirePermission } from "../../shell/auth/RequirePermission";
import { navItemsFor } from "../../shell/modules/assemble";
import { reportingModule, financeModule } from "./module";

describe("the reporting routes", () => {
  it("protect each route with the appropriate permission requirement", () => {
    expect(reportingModule.routes.length).toBe(7);
    for (const route of reportingModule.routes) {
      expect(isValidElement(route.element) && route.element.type === RequirePermission, String(route.path)).toBe(true);
      const perms = (route.element as { props: { anyOf: string[] } }).props.anyOf;
      if (route.path === "reporting/targets" || route.path === "reporting/revenue-estimator") {
        expect(perms).toEqual(["rpt.target.view"]);
      } else if (route.path === "reporting/profit-centers") {
        expect(perms).toEqual(["rpt.profit_center.view"]);
      } else {
        expect(perms).toEqual(["rpt.report.run"]);
      }
    }
  });

  it("show the menu entries only to users with the required permissions", () => {
    expect(reportingModule.navItems.length).toBe(5);
    expect(reportingModule.navItems[0].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(reportingModule.navItems[1].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(reportingModule.navItems[2].requiredPermissions).toEqual(["rpt.profit_center.view"]);
    expect(reportingModule.navItems[3].requiredPermissions).toEqual(["rpt.target.view"]);
    expect(reportingModule.navItems[4].requiredPermissions).toEqual(["rpt.target.view"]);
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
      "/reporting/targets",
      "/reporting/revenue-estimator"
    ]);
  });
});

describe("the finance routes", () => {
  it("protect each finance route with appropriate permission requirement", () => {
    expect(financeModule.routes.length).toBe(2);
    for (const route of financeModule.routes) {
      expect(isValidElement(route.element) && route.element.type === RequirePermission, String(route.path)).toBe(true);
      const perms = (route.element as { props: { anyOf: string[] } }).props.anyOf;
      if (route.path === "reporting/assets") {
        expect(perms).toEqual(["rpt.asset.view"]);
      } else {
        expect(perms).toEqual(["rpt.report.run"]);
      }
    }
  });

  it("shows finance navigation entries to authorized users", () => {
    expect(financeModule.navItems.length).toBe(2);
    expect(financeModule.navItems[0].requiredPermissions).toEqual(["rpt.report.run"]);
    expect(financeModule.navItems[1].requiredPermissions).toEqual(["rpt.asset.view"]);

    const fedView = {
      policyClass: "FEDERATION_VIEW" as const,
      permissions: ["rpt.report.run", "rpt.asset.view"]
    };
    const items = navItemsFor([financeModule], fedView);
    expect(items.map((i) => i.to)).toEqual([
      "/reporting/finance",
      "/reporting/assets"
    ]);
  });
});
