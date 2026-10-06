import { describe, expect, it } from "vitest";
import { dayBefore, defaultExportPeriod, journalFileName, periodIsOpen, templateText } from "./integrationView";
import messages from "./integration.messages.json";
import { integrationModule } from "./module";

describe("the export form", () => {
  it("opens on the first of the month two months back to yesterday, a closed day", () => {
    // Fixed dates: the test never depends on today (Asia/Colombo midnight).
    expect(defaultExportPeriod("2026-09-29")).toEqual({ from: "2026-07-01", to: "2026-09-28" });
    expect(defaultExportPeriod("2026-01-15")).toEqual({ from: "2025-11-01", to: "2026-01-14" });
  });

  it("steps back one day across a month and a year", () => {
    expect(dayBefore("2026-03-01")).toBe("2026-02-28");
    expect(dayBefore("2028-03-01")).toBe("2028-02-29");
    expect(dayBefore("2026-01-01")).toBe("2025-12-31");
  });

  it("knows a period ending today or later is still open", () => {
    expect(periodIsOpen("2026-09-28", "2026-09-29")).toBe(false);
    expect(periodIsOpen("2026-09-29", "2026-09-29")).toBe(true);
    expect(periodIsOpen("2026-10-05", "2026-09-29")).toBe(true);
    expect(periodIsOpen("", "2026-09-29")).toBe(false);
  });

  it("names the file after the period, and marks a provisional one", () => {
    expect(journalFileName("2026-09-01", "2026-09-30")).toBe("journal_2026-09-01_2026-09-30.csv");
    expect(journalFileName("2026-09-01", "2026-09-30", false)).toBe("journal_2026-09-01_2026-09-30.csv");
    expect(journalFileName("2026-09-01", "2026-09-30", true)).toBe("journal_2026-09-01_2026-09-30_PROVISIONAL.csv");
  });
});

describe("a template in the reader's language", () => {
  it("is the language's own text when it has one", () => {
    expect(templateText("Invoice", "ඉන්වොයිසිය", "விலைப்பட்டியல்", "si")).toEqual({ text: "ඉන්වොයිසිය", fallback: false });
    expect(templateText("Invoice", "ඉන්වොයිසිය", "விலைப்பட்டியல்", "ta")).toEqual({
      text: "விலைப்பட்டியல்",
      fallback: false
    });
  });

  it("falls back to English, and says so, when the language has none", () => {
    expect(templateText("Invoice", null, undefined, "ta")).toEqual({ text: "Invoice", fallback: true });
    expect(templateText("Invoice", null, null, "en")).toEqual({ text: "Invoice", fallback: false });
  });
});

describe("the module", () => {
  it("has every message in Sinhala and Tamil as well as English", () => {
    const en = Object.keys(messages.en).sort();
    expect(Object.keys(messages.si).sort()).toEqual(en);
    expect(Object.keys(messages.ta).sort()).toEqual(en);
  });

  it("asks for the codes of its slice, and each entry for its own", () => {
    expect(integrationModule.requiredPermissions).toEqual([
      "int.journal.read",
      "int.journal.export",
      "int.notify.view",
      "int.notify.manage"
    ]);
    expect(integrationModule.navItems.map((item) => item.to)).toEqual([
      "/integration/journal",
      "/integration/notifications"
    ]);
  });
});
