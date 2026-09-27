import { describe, expect, it } from "vitest";
import { csvFileName, defaultPeriod, isNumeric, isoDay, queryOf } from "./reportView";

describe("report view", () => {
  it("writes a calendar date as the API does", () => {
    expect(isoDay(new Date(2026, 8, 7))).toBe("2026-09-07");
  });

  it("opens a period report on the month so far", () => {
    expect(defaultPeriod(new Date(2026, 8, 27))).toEqual({ from: "2026-09-01", to: "2026-09-27" });
  });

  it("sends only the parameters the definition takes", () => {
    const form = { from: "2026-09-01", to: "2026-09-27", locationId: "loc" };
    expect(queryOf({ period: true, location: false }, form)).toEqual({ from: "2026-09-01", to: "2026-09-27" });
    expect(queryOf({ period: false, location: true }, form)).toEqual({ locationId: "loc" });
    expect(queryOf({ period: false, location: true }, { ...form, locationId: "" })).toEqual({});
  });

  it("names the CSV as the server does", () => {
    expect(csvFileName("invoices-issued", { from: "2026-09-01", to: "2026-09-27" })).toBe(
      "invoices-issued_2026-09-01_2026-09-27.csv"
    );
    expect(csvFileName("stock-position", {})).toBe("stock-position.csv");
  });

  it("right-aligns quantities and money only", () => {
    expect(isNumeric("QTY")).toBe(true);
    expect(isNumeric("MONEY")).toBe(true);
    expect(isNumeric("DATE")).toBe(false);
  });
});
