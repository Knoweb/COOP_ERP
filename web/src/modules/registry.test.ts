import { describe, expect, it } from "vitest";
import { MODULES } from "./registry";

// The navigation lists one link per entry of every module (shell/nav/Navigation.tsx), keyed by
// its route, so two entries with one route would be two links to one page. Labels are not
// compared here: `make new-module` writes a module whose Sinhala and Tamil texts are still the
// greeting until they are translated (its README step 5), and the scaffolder's proof runs these
// tests on that module; the e2e of login and language (web/e2e/login-and-language.spec.ts)
// finds a repeated label in the navigation of a real user.
describe("the modules' navigation entries", () => {
  it("go to different routes", () => {
    const routes = MODULES.flatMap((module) => module.navItems).map((entry) => entry.to);
    expect(routes).toEqual([...new Set(routes)]);
  });
});
