import { describe, expect, it } from "vitest";
import { chooseLocale } from "./messages";

describe("the language of the screen", () => {
  it("is the user's own language when the token names one", () => {
    expect(chooseLocale("ta", "?lang=si")).toBe("ta");
  });

  it("is the one in the address before login", () => {
    expect(chooseLocale(null, "?lang=si")).toBe("si");
  });

  it("is English otherwise, also for a language we do not have", () => {
    expect(chooseLocale(null, "")).toBe("en");
    expect(chooseLocale(null, "?lang=fr")).toBe("en");
  });
});
