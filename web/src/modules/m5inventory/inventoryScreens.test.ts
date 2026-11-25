import { describe, expect, it } from "vitest";
import type { LotBalance, NegativeLot, WriteOff } from "./inventoryApi";
import { expiryRows, lossCategories, unacknowledgedNegativeLotCount } from "./inventoryScreens";

const lot = (stockLotId: string, expiryDate: string | undefined, qtyOnHand: number): LotBalance => ({
  stockLotId,
  locationId: "01900000-0000-7000-8000-000000000001",
  skuId: `01900000-0000-7000-8000-00000000000${stockLotId}`,
  batchId: `01900000-0000-7000-8000-00000000001${stockLotId}`,
  condition: "GOOD",
  qtyOnHand,
  negative: qtyOnHand < 0,
  expiryDate
});

describe("the expiry list", () => {
  it("shows only positive dated stock in earliest-expiry order using calendar days", () => {
    expect(expiryRows([lot("1", "2026-11-05", 2), lot("2", undefined, 4), lot("3", "2026-10-24", 1), lot("4", "2026-10-26", 0)], "2026-10-25"))
      .toMatchObject([
        { stockLotId: "3", daysRemaining: -1 },
        { stockLotId: "1", daysRemaining: 11 }
      ]);
  });
});

describe("the loss and shrinkage summary", () => {
  it("totals posted documents only and groups their recorded values", () => {
    const writeOffs = [
      { writeOffId: "1", category: "THEFT", status: "POSTED", value: 125 },
      { writeOffId: "2", category: "THEFT", status: "POSTED", value: 75 },
      { writeOffId: "3", category: "OTHER", status: "DRAFT", value: 999 }
    ] as WriteOff[];
    expect(lossCategories(writeOffs)).toEqual([{ category: "THEFT", documents: 2, value: 200 }]);
  });

  it("counts affected lots without adding quantities of incompatible units", () => {
    const lots = [
      { qtyOnHand: -3, acknowledgedAt: null },
      { qtyOnHand: -2.5 },
      { qtyOnHand: -9, acknowledgedAt: "2026-10-24T00:00:00Z" }
    ] as NegativeLot[];
    expect(unacknowledgedNegativeLotCount(lots)).toBe(2);
  });
});
