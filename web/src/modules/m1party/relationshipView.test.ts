import { describe, expect, it } from "vitest";
import type { Relationship } from "./partyApi";
import { firstLimitDate, latestPerPair, limitReady, limitRequest, pairHistory, relationshipChip } from "./relationshipView";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const OTHER = "0190f0de-0000-7000-8000-0000000000e2";

function row(id: string, buyer: string, from: string, to?: string, status: Relationship["status"] = "ACTIVE"): Relationship {
  return {
    relationshipId: id,
    sellerEntityId: SELLER,
    buyerEntityId: buyer,
    creditLimit: 5000000,
    paymentTermsDays: 30,
    discrepancyWindowDays: 7,
    orderLockHoursBeforeEta: 24,
    allocationRule: "FCFS",
    status,
    effectiveFrom: from,
    effectiveTo: to ?? null
  };
}

describe("the relationship screens' arithmetic", () => {
  const first = row("r1", BUYER, "2026-04-01", "2026-06-30");
  const second = row("r2", BUYER, "2026-07-01");
  const other = row("r3", OTHER, "2026-05-01");

  it("reads a pair's history latest first and lists one row per buyer", () => {
    expect(pairHistory([first, other, second], first).map((r) => r.relationshipId)).toEqual(["r2", "r1"]);
    expect(latestPerPair([first, other, second]).map((r) => r.relationshipId).sort()).toEqual(["r2", "r3"]);
    expect(latestPerPair([first, row("r4", BUYER, "2026-07-01", undefined, "REPLACED")]).map((r) => r.relationshipId)).toEqual(["r1"]);
  });

  it("shows a closed row as ended and the row in force as in force", () => {
    expect(relationshipChip(first, "2026-09-29")).toBe("void");
    expect(relationshipChip(second, "2026-09-29")).toBe("issued");
    expect(relationshipChip(row("r5", BUYER, "2026-01-01", undefined, "SUSPENDED"), "2026-09-29")).toBe("alert");
  });

  it("starts a new limit today, or tomorrow when the row began today (CR-21A-2)", () => {
    expect(firstLimitDate(second, "2026-09-29")).toBe("2026-09-29");
    expect(firstLimitDate(row("r6", BUYER, "2026-09-29"), "2026-09-29")).toBe("2026-09-30");
  });

  it("sends the limit only, with the reason for the audit record", () => {
    expect(limitReady("3000000", "2026-09-29")).toBe(true);
    expect(limitReady("30.555", "2026-09-29")).toBe(false);
    expect(limitReady("-1", "2026-09-29")).toBe(false);
    expect(limitReady("", "2026-09-29")).toBe(false);
    expect(limitRequest("3000000.50", "2026-09-29", "REVIEW", null)).toEqual({
      effectiveFrom: "2026-09-29",
      creditLimit: 3000000.5,
      reasonCode: "REVIEW",
      reasonText: undefined
    });
  });
});
