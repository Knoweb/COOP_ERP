import type { ChipState } from "../../shell/components/StateChip";
import type { Count, CountLineRequest, LotBalance, Recipe, RequestWriteOffRequest, WriteOff } from "./inventoryApi";

// The pure rules behind the stock control screens (counts, write-offs, repacks), kept apart from
// the pages so vitest can prove them. The server decides everything that matters (tolerance,
// bands, who may act); the screen only shows what a person typed and what comes next.

/** What a counter typed against one lot: a quantity, or a reason to skip it. */
export type CountEntry = { counted: string; skip: string };

export function lotKey(batchId: string, condition: string): string {
  return `${batchId}/${condition}`;
}

/** A typed quantity as a number: zero or more with at most three decimals, or null. */
export function quantityOf(text: string | undefined): number | null {
  const trimmed = (text ?? "").trim();
  if (!/^\d+(\.\d{1,3})?$/.test(trimmed)) {
    return null;
  }
  return Number(trimmed);
}

/**
 * The variance to show once a quantity is entered (25A section 8: "variance shown only after
 * entry"): counted minus what the book said at the start. Null before entry. The server measures
 * again against the book at submit, so sales made meanwhile are not a variance.
 */
export function varianceOf(expected: number, entry: CountEntry | undefined): number | null {
  const counted = quantityOf(entry?.counted);
  if (counted === null) {
    return null;
  }
  return Math.round((counted - expected) * 1000) / 1000;
}

/**
 * The lines of a submit: every lot of the sheet counted or skipped with a reason. Null while one
 * is neither, so the submit stays disabled ("all lines counted or skipped").
 */
export function countLinesOf(count: Count, entries: Record<string, CountEntry>): CountLineRequest[] | null {
  const lines: CountLineRequest[] = [];
  for (const lot of count.expectation) {
    const entry = entries[lotKey(lot.batchId, lot.condition)];
    const counted = quantityOf(entry?.counted);
    const skip = (entry?.skip ?? "").trim();
    if (counted !== null) {
      lines.push({ batchId: lot.batchId, condition: lot.condition, countedQty: counted });
    } else if (skip !== "") {
      lines.push({ batchId: lot.batchId, condition: lot.condition, skipReason: skip });
    } else {
      return null;
    }
  }
  return lines;
}

/** The look of a count's state: being worked on, waiting for somebody, or done. */
export function countChip(status: Count["status"]): ChipState {
  if (status === "CLOSED") {
    return "issued";
  }
  return status === "VARIANCE_REVIEW" ? "alert" : "draft";
}

/** The look of a write-off's state. */
export function writeOffChip(status: WriteOff["status"]): ChipState {
  switch (status) {
    case "POSTED":
      return "issued";
    case "REJECTED":
      return "void";
    case "DRAFT":
      return "draft";
    default:
      return "alert";
  }
}

/** The step a write-off waits for (25A section 8: "shows who must witness/approve"). */
export function nextWriteOffStep(status: WriteOff["status"]): "submit" | "witness" | "approve" | null {
  switch (status) {
    case "DRAFT":
      return "submit";
    case "REQUESTED":
      return "witness";
    case "WITNESSED":
      return "approve";
    default:
      return null;
  }
}

/** A write-off's photographs are missing when its category or location needs them and none is there. */
export function photosMissing(writeOff: WriteOff): boolean {
  return writeOff.photosRequired && writeOff.photos.length === 0;
}

/** The lines of a write-off from the quantities typed against the location's lots (keyed by lot). */
export function writeOffLinesOf(lots: LotBalance[], quantities: Record<string, string>): RequestWriteOffRequest["lines"] {
  return lots
    .map((lot) => ({
      batchId: lot.batchId,
      condition: lot.condition,
      qty: quantityOf(quantities[lotKey(lot.batchId, lot.condition)]) ?? 0
    }))
    .filter((line) => line.qty > 0);
}

/**
 * What a repack should yield (doc 25 section 3.6): the recipe scaled to the quantity taken, less
 * its expected loss; three decimals, as the server computes it.
 */
export function expectedOutputOf(recipe: Recipe, inputQty: number): number {
  const expected = (inputQty * recipe.outputQty * (100 - recipe.expectedLossPct)) / (recipe.inputQty * 100);
  return Math.round(expected * 1000) / 1000;
}
