import { describe, expect, it } from "vitest";
import type { Receipt } from "./posApi";
import { receiptLook, tenderKinds, tillNumber } from "./posView";

const receipt = (over: Partial<Receipt>): Receipt => ({
  documentId: "d1",
  locationId: "l1",
  issuedAt: "2026-09-28T04:30:00Z",
  flags: [],
  lines: [],
  tenders: [],
  ...over
});

describe("the receipt screens' decisions", () => {
  it("names the till by its position number, and nothing when the position is unknown", () => {
    const positions = [
      { tillPositionId: "p1", locationId: "l1", positionNo: 1, status: "ACTIVE" as const, primary: true },
      { tillPositionId: "p2", locationId: "l1", positionNo: 2, status: "ACTIVE" as const, primary: false }
    ];
    expect(tillNumber(positions, "p2")).toBe(2);
    expect(tillNumber(positions, "p9")).toBeNull();
    expect(tillNumber(undefined, "p1")).toBeNull();
    expect(tillNumber(positions, undefined)).toBeNull();
  });

  it("lists each kind of tender once, in the till's order", () => {
    const tenders = [
      { seq: 2, kind: "CARD_REF", amount: 100 },
      { seq: 1, kind: "CASH", amount: 50 },
      { seq: 3, kind: "CASH", amount: 10 }
    ];
    expect(tenderKinds(receipt({ tenders }))).toEqual(["CASH", "CARD_REF"]);
  });

  it("marks a receipt central flagged as an alert", () => {
    expect(receiptLook(receipt({}))).toBe("issued");
    expect(receiptLook(receipt({ flags: ["SESSION_UNKNOWN"] }))).toBe("alert");
  });
});
