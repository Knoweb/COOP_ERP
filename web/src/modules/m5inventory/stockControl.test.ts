import { describe, expect, it } from "vitest";
import type { Count, LotBalance, Recipe, WriteOff } from "./inventoryApi";
import {
  countChip,
  countLinesOf,
  expectedOutputOf,
  lotKey,
  nextWriteOffStep,
  photosMissing,
  quantityOf,
  varianceOf,
  writeOffChip,
  writeOffLinesOf
} from "./stockControl";

const RICE = "0190e67c-0000-7000-8000-00000000b001";
const DHAL = "0190e67c-0000-7000-8000-00000000b002";

const counting: Count = {
  taskId: "task",
  locationId: "stores",
  scopeKind: "SKUS",
  skuIds: ["rice"],
  scheduledFor: "2026-10-13",
  status: "COUNTING",
  expectation: [
    { batchId: RICE, skuId: "rice", condition: "GOOD", expectedQty: 37 },
    { batchId: DHAL, skuId: "dhal", condition: "GOOD", expectedQty: 12 }
  ],
  lines: []
};

describe("the stocktake sheet", () => {
  it("reads a counted quantity of zero or more with at most three decimals", () => {
    expect(quantityOf("35")).toBe(35);
    expect(quantityOf(" 0 ")).toBe(0);
    expect(quantityOf("2.125")).toBe(2.125);
    expect(quantityOf("2.1255")).toBeNull();
    expect(quantityOf("-1")).toBeNull();
    expect(quantityOf("")).toBeNull();
    expect(quantityOf(undefined)).toBeNull();
  });

  it("shows the variance only once a quantity is entered", () => {
    expect(varianceOf(37, undefined)).toBeNull();
    expect(varianceOf(37, { counted: "", skip: "blocked" })).toBeNull();
    expect(varianceOf(37, { counted: "35", skip: "" })).toBe(-2);
    expect(varianceOf(0.3, { counted: "0.4", skip: "" })).toBe(0.1);
  });

  it("submits only when every lot is counted or skipped with a reason", () => {
    const rice = lotKey(RICE, "GOOD");
    const dhal = lotKey(DHAL, "GOOD");
    expect(countLinesOf(counting, {})).toBeNull();
    expect(countLinesOf(counting, { [rice]: { counted: "35", skip: "" } })).toBeNull();
    expect(countLinesOf(counting, { [rice]: { counted: "35", skip: "" }, [dhal]: { counted: "", skip: "  " } })).toBeNull();
    expect(
      countLinesOf(counting, { [rice]: { counted: "35", skip: "" }, [dhal]: { counted: "", skip: "Shelf blocked" } })
    ).toEqual([
      { batchId: RICE, condition: "GOOD", countedQty: 35 },
      { batchId: DHAL, condition: "GOOD", skipReason: "Shelf blocked" }
    ]);
  });

  it("gives each state of a count its look", () => {
    expect(countChip("COUNTING")).toBe("draft");
    expect(countChip("SCHEDULED")).toBe("draft");
    expect(countChip("VARIANCE_REVIEW")).toBe("alert");
    expect(countChip("CLOSED")).toBe("issued");
  });
});

describe("the damage and expiry register", () => {
  const writeOff = (status: WriteOff["status"], photosRequired = false, photos = 0): WriteOff => ({
    writeOffId: "w",
    locationId: "stores",
    category: "THEFT",
    status,
    remoteWitness: false,
    photosRequired,
    lines: [],
    photos: Array.from({ length: photos }, (_, i) => ({ attachmentId: `p${i}`, status: "PENDING" as const }))
  });

  it("names the next step and who takes it", () => {
    expect(nextWriteOffStep("DRAFT")).toBe("submit");
    expect(nextWriteOffStep("REQUESTED")).toBe("witness");
    expect(nextWriteOffStep("WITNESSED")).toBe("approve");
    expect(nextWriteOffStep("POSTED")).toBeNull();
    expect(nextWriteOffStep("REJECTED")).toBeNull();
    expect(writeOffChip("POSTED")).toBe("issued");
    expect(writeOffChip("REJECTED")).toBe("void");
    expect(writeOffChip("WITNESSED")).toBe("alert");
  });

  it("holds the submit until a required photograph is there", () => {
    expect(photosMissing(writeOff("DRAFT", true, 0))).toBe(true);
    expect(photosMissing(writeOff("DRAFT", true, 1))).toBe(false);
    expect(photosMissing(writeOff("DRAFT", false, 0))).toBe(false);
  });

  it("builds the lines from the quantities typed against the lots, blanks left out", () => {
    const lot = (batchId: string, condition: "GOOD" | "DAMAGED"): LotBalance => ({
      stockLotId: `${batchId}-${condition}`,
      locationId: "stores",
      skuId: "flour",
      batchId,
      condition,
      qtyOnHand: 10,
      negative: false
    });
    expect(
      writeOffLinesOf([lot(RICE, "GOOD"), lot(RICE, "DAMAGED"), lot(DHAL, "GOOD")], {
        [lotKey(RICE, "DAMAGED")]: "2",
        [lotKey(DHAL, "GOOD")]: "x"
      })
    ).toEqual([{ batchId: RICE, condition: "DAMAGED", qty: 2 }]);
  });
});

describe("the production sheet", () => {
  it("scales the recipe to the quantity taken, less its expected loss (doc 13 scenario 3)", () => {
    const recipe: Recipe = {
      recipeId: "r",
      name: "R-012",
      inputSkuId: "loose",
      inputQty: 50,
      outputSkuId: "pack",
      outputQty: 50,
      expectedLossPct: 2,
      status: "ACTIVE"
    };
    expect(expectedOutputOf(recipe, 50)).toBe(49);
    expect(expectedOutputOf({ ...recipe, inputQty: 5, outputQty: 1, expectedLossPct: 0 }, 10)).toBe(2);
  });
});
