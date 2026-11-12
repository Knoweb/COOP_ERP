import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MODULES } from "../../modules/registry";
import { NavIcon } from "./NavIcon";

afterEach(cleanup);

describe("NavIcon", () => {
  it("renders a distinct, uniform 18x18 stroke icon for every module navigation entry", () => {
    const routes = MODULES.flatMap((module) => module.navItems).map((item) => item.to);
    const signatures = new Map<string, string>();

    for (const route of routes) {
      const { container } = render(<NavIcon to={route} />);
      const svg = container.querySelector("svg");
      expect(svg, `missing svg for ${route}`).not.toBeNull();
      expect(svg?.getAttribute("viewBox")).toBe("0 0 24 24");
      expect(svg?.getAttribute("width")).toBe("18");
      expect(svg?.getAttribute("height")).toBe("18");
      expect(svg?.getAttribute("stroke-width")).toBe("2");
      expect(svg?.getAttribute("aria-hidden")).toBe("true");
      const markup = svg?.innerHTML.trim() ?? "";
      expect(markup.length).toBeGreaterThan(0);

      const previousRoute = signatures.get(markup);
      expect(previousRoute, `${route} shares an icon with ${previousRoute}`).toBeUndefined();
      signatures.set(markup, route);
    }
  });
});

