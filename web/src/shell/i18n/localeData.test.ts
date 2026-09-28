import { afterEach, describe, expect, it, vi } from "vitest";

// A browser without Sinhala and Tamil number and date data (an embedded Chromium with a reduced
// ICU) is played by the two should-polyfill answers; the polyfills themselves are the real ones.
const missing = { numbers: false, dates: false };
vi.mock("@formatjs/intl-numberformat/should-polyfill.js", () => ({
  shouldPolyfill: (locale: string) => (missing.numbers ? locale : undefined)
}));
vi.mock("@formatjs/intl-datetimeformat/should-polyfill.js", () => ({
  shouldPolyfill: (locale: string) => (missing.dates ? locale : undefined)
}));

describe("the Sinhala and Tamil number and date data", () => {
  afterEach(() => {
    missing.numbers = false;
    missing.dates = false;
  });

  it("loads nothing in a browser that has it", async () => {
    const { ensureLocaleData, missingLocaleData } = await import("./localeData");
    const before = Intl.NumberFormat;

    expect(missingLocaleData()).toEqual({ numbers: false, dates: false });
    await ensureLocaleData();
    expect(Intl.NumberFormat).toBe(before);
  });

  it("puts the polyfills in, with si and ta, where the browser lacks it, so react-intl finds the locale", async () => {
    missing.numbers = true;
    missing.dates = true;
    const { ensureLocaleData } = await import("./localeData");

    await ensureLocaleData();

    expect(Intl.NumberFormat.supportedLocalesOf(["si", "ta"])).toEqual(["si", "ta"]);
    expect(Intl.DateTimeFormat.supportedLocalesOf(["si", "ta"])).toEqual(["si", "ta"]);
    // The business time zone still works under the polyfill (formats.ts).
    expect(
      new Intl.DateTimeFormat("si", { timeZone: "Asia/Colombo", hour: "numeric", hourCycle: "h23" }).format(
        new Date("2026-09-28T00:00:00Z")
      )
    ).toContain("5");
    // The trading screens' business date still reads yyyy-mm-dd under the polyfill (the e2e broke
    // on "Invalid time value" when it was written with the en-CA format the polyfill lacks).
    const { businessToday } = await import("../../modules/m4trading/tradingView");
    expect(businessToday(new Date("2026-09-27T20:00:00Z"))).toBe("2026-09-28");
  }, 15000);
});
