// The small decisions of the receipt screens, kept out of the components so they are tested
// without rendering: which till a receipt came from, how it was paid, which chip it wears.

import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";
import type { Receipt, TillPosition } from "./posApi";

/** The till's number at the shop (1, 2 ...), or null when the position is not known. */
export function tillNumber(positions: TillPosition[] | undefined, tillPositionId: string | undefined): number | null {
  if (!positions || !tillPositionId) {
    return null;
  }
  return positions.find((p) => p.tillPositionId === tillPositionId)?.positionNo ?? null;
}

/**
 * The kinds of tender of a receipt, each once, in the till's order: ["CASH"], ["CASH", "CARD"].
 * The screen translates each kind; an unknown kind is shown as the till sent it.
 */
export function tenderKinds(receipt: Receipt): string[] {
  return [...new Set([...receipt.tenders].sort((a, b) => a.seq - b.seq).map((t) => t.kind))];
}

/**
 * A receipt central flagged (LOCATION_MISMATCH, SESSION_UNKNOWN ...) needs a look; any other is
 * simply issued. A till's fact is never refused, only flagged (AGENTS.md).
 */
export function receiptLook(receipt: Receipt): ChipState {
  return receipt.flags.length > 0 ? "alert" : "issued";
}

export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}
