import { describe, expect, it } from "vitest";
import { defaultExportPeriod, isoDay, journalFileName, templateText } from "./integrationView";
import messages from "./integration.messages.json";
import { integrationModule } from "./module";

describe("the export form", () => {
  it("opens on the first of the month two months back to today, from the local day", () => {
    // A fixed date: the test never depends on today (Asia/Colombo midnight).
    expect(defaultExportPeriod(new Date(2026, 8, 29, 23, 30))).toEqual({ from: "2026-07-01", to: "2026-09-29" });
    expect(defaultExportPeriod(new Date(2026, 0, 15))).toEqual({ from: "2025-11-01", to: "2026-01-15" });
  });

  it("writes a calendar day with two-digit month and day", () => {
    expect(isoDay(new Date(2026, 2, 5))).toBe("2026-03-05");
  });

  it("names the file after the period", () => {
    expect(journalFileName("2026-09-01", "2026-09-30")).toBe("journal_2026-09-01_2026-09-30.csv");
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
