import { describe, expect, it } from "vitest";
import { exceptionLink, isNumeric, tileLink, trendHeights } from "./reportView";

describe("dashboard and exception queue view", () => {
  it("opens a tile's report, or the exception queue", () => {
    expect(tileLink("receivables-ageing")).toBe("/reporting/reports/receivables-ageing");
    expect(tileLink("exceptions")).toBe("/reporting/exceptions");
    expect(tileLink(undefined)).toBeUndefined();
  });

  it("sends each exception to the page where it is dealt with", () => {
    const id = "subject-1";
    expect(exceptionLink({ kind: "CHEQUE_BOUNCED", subjectId: id })).toBe(`/trading/payments/${id}`);
    expect(exceptionLink({ kind: "DISCREPANCY_OPEN", subjectId: id })).toBe(`/trading/discrepancies/${id}`);
    expect(exceptionLink({ kind: "INVOICE_DISPUTED", subjectId: id })).toBe(`/trading/invoices/${id}`);
    expect(
      exceptionLink({ kind: "EXPOSURE_WARNING", subjectId: id, role: "SELLER", counterpartyEntityId: "b" })
    ).toBe("/trading/accounts/SELLER/b");
    expect(exceptionLink({ kind: "NEGATIVE_STOCK", subjectId: id })).toBeUndefined();
  });

  it("scales a trend against its largest week, and a flat zero trend stays flat", () => {
    expect(trendHeights(["0", "50.00", "100.00", "25"])).toEqual([0, 50, 100, 25]);
    expect(trendHeights(["0", "0"])).toEqual([0, 0]);
  });

  it("right-aligns counts and rates with the other numbers", () => {
    expect(isNumeric("COUNT")).toBe(true);
    expect(isNumeric("PERCENT")).toBe(true);
    expect(isNumeric("TEXT")).toBe(false);
  });
});
