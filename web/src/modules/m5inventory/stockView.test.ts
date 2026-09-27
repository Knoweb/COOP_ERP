import { describe, expect, it } from "vitest";
import messages from "./inventory.messages.json" with { type: "json" };
import { chipOf, lineOf, rowReady, transferLinesOf, type CountedRow } from "./stockView";
import type { LotBalance } from "./inventoryApi";

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
