import { describe, expect, it } from "vitest";
import { messages } from "../shell/i18n/messages";
import { MODULES } from "./registry";

// The navigation lists one link per entry of every module the user may open, named by the
// entry's label. Two entries with the same label in one language are two links a user cannot
// tell apart (a module scaffolded by `make new-module` starts with the greeting texts in
// Sinhala and Tamil, which is how m3pricing once showed "Greetings" twice).
describe("the modules' navigation entries", () => {
  const entries = MODULES.flatMap((module) => module.navItems);

  it("go to different routes", () => {
    const routes = entries.map((entry) => entry.to);
    expect(routes).toEqual([...new Set(routes)]);
  });

  for (const language of Object.keys(messages) as Array<keyof typeof messages>) {
    it(`have different labels in ${language}`, () => {
      const labels = entries.map((entry) => messages[language][entry.labelId] ?? entry.labelId);
      const repeated = labels.filter((label, index) => labels.indexOf(label) !== index);
      expect(repeated).toEqual([]);
    });
  }
});
