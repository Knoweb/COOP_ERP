import { describe, expect, it } from "vitest";
import messages from "./inventory.messages.json" with { type: "json" };
import {
  chipOf,
  isSyntheticBatchNo,
  lineOf,
  requestChip,
  requestLinesOf,
  rowReady,
  sourceFor,
  transferLinesOf,
  type CountedRow
} from "./stockView";
import type { Location, LotBalance, TransferRequest } from "./inventoryApi";

describe("the transfer request (M4-10)", () => {
  const request = (fromLocationId?: string): TransferRequest => ({
    requestId: "r",
    fromLocationId,
    toLocationId: "shop",
    status: "REQUESTED",
    requestedAt: "2026-09-29T02:45:00Z",
    lines: []
  });
  const place = (locationId: string, locationType: "WAREHOUSE" | "SHOP") =>
    ({ locationId, locationType }) as unknown as Location;

  it("sends only the items given a quantity above zero", () => {
    expect(requestLinesOf({ a: "6", b: "", c: "0", d: "x" })).toEqual([{ skuId: "a", qty: 6 }]);
  });

  it("approves from the chosen place, else the shop's, else the only warehouse", () => {
    const places = [place("stores", "WAREHOUSE"), place("shop", "SHOP")];
    expect(sourceFor(request(), { r: "other" }, places)).toBe("other");
    expect(sourceFor(request("named"), {}, places)).toBe("named");
    expect(sourceFor(request(), {}, places)).toBe("stores");
    expect(sourceFor(request(), {}, [...places, place("second", "WAREHOUSE")])).toBeUndefined();
  });

  it("shows a waiting request as a draft and a refused one as void", () => {
    expect(requestChip("REQUESTED")).toBe("draft");
    expect(requestChip("APPROVED")).toBe("issued");
    expect(requestChip("REJECTED")).toBe("void");
  });
});

describe("the transfer form", () => {
  const lot = (batchId: string): LotBalance => ({
    stockLotId: `lot-${batchId}`,
    locationId: "w",
    skuId: "s",
    batchId,
    condition: "GOOD",
    qtyOnHand: 10,
    negative: false
  });

  it("sends only the lots with a quantity above zero", () => {
    expect(transferLinesOf([lot("a"), lot("b"), lot("c"), lot("d")], { a: " 4 ", b: "", c: "0", d: "x" })).toEqual([
      { batchId: "a", qty: 4 }
    ]);
  });
});

const ROW: CountedRow = {
  skuId: "0190e620-0000-7000-8000-000000000001",
  label: "SKU-1 Dhal",
  batchNo: " D-7 ",
  expiryDate: "2027-05-31",
  printedMrp: "",
  qty: "24",
  unitCost: "310.5",
  condition: "GOOD"
};

describe("the stock view", () => {
  it("shows a posted opening balance as issued and the rest as a draft", () => {
    expect(chipOf("DRAFT")).toBe("draft");
    expect(chipOf("SIGNED_ENTITY")).toBe("draft");
    expect(chipOf("POSTED")).toBe("issued");
  });

  it("sends the batch as counted and leaves out what was not typed", () => {
    expect(lineOf(ROW)).toEqual({
      skuId: ROW.skuId,
      batchNo: "D-7",
      expiryDate: "2027-05-31",
      printedMrp: undefined,
      qty: 24,
      unitCost: 310.5,
      condition: "GOOD"
    });
  });

  it("is ready once it names an item, a quantity above zero and a cost", () => {
    expect(rowReady(ROW)).toBe(true);
    expect(rowReady({ ...ROW, qty: "0" })).toBe(false);
    expect(rowReady({ ...ROW, unitCost: "" })).toBe(false);
    expect(rowReady({ ...ROW, skuId: "" })).toBe(false);
  });

  it("recognises M2's own synthetic shape S-<document>-<line>, not a real batch number", () => {
    expect(isSyntheticBatchNo("S-OPB-01a0e468-9")).toBe(true);
    expect(isSyntheticBatchNo("DEMO-2026-1")).toBe(false);
    expect(isSyntheticBatchNo(undefined)).toBe(false);
  });

  it("has a text for every state and condition in every language", () => {
    for (const locale of ["en", "si", "ta"] as const) {
      const catalogue = messages[locale] as Record<string, string>;
      for (const id of [
        "inventory.opening.status.DRAFT",
        "inventory.opening.status.SIGNED_ENTITY",
        "inventory.opening.status.POSTED",
        "inventory.condition.GOOD",
        "inventory.condition.DAMAGED"
      ]) {
        expect(catalogue[id]).toBeTruthy();
      }
    }
  });
});
