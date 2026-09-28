import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";
import type { ControlPrice, LineOutcome, PriceList } from "./pricingApi";

/** The look of a price list's state (doc 30 section 2.2): a draft is being written, a published version is in force. */
export function chipOf(status: PriceList["status"]): ChipState {
  switch (status) {
    case "DRAFT":
      return "draft";
    case "PUBLISHED":
      return "issued";
    default:
      return "void";
  }
}

/** The problem's title, which the server has already translated; the fallback otherwise. */
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/**
 * The web message id of a line outcome's reason (a backend message id such as
 * m3.price_list.line.duplicate): the same last word under pricing.reason.
 */
export function reasonMessageId(reason: string): string {
  return `pricing.reason.${reason.substring(reason.lastIndexOf(".") + 1)}`;
}

/** The binding ceiling the server named for a line (23A section 5: "ceiling: {kind, value, ref}"), or null. */
export function bindingCeiling(
  outcome: LineOutcome | undefined
): { kind: "CONTROL_PRICE" | "MRP"; value: number; ref: string } | null {
  if (!outcome?.ceilingKind || outcome.ceilingValue == null) {
    return null;
  }
  return {
    kind: outcome.ceilingKind === "MRP" ? "MRP" : "CONTROL_PRICE",
    value: outcome.ceilingValue,
    ref: outcome.ceilingRef ?? ""
  };
}

/** Where a control price stands on a date: not yet, in force, or ended. Dates are ISO text, compared as text. */
export function controlPriceState(row: ControlPrice, date: string): "future" | "in_force" | "ended" {
  if (row.effectiveFrom > date) {
    return "future";
  }
  if (row.effectiveTo && row.effectiveTo < date) {
    return "ended";
  }
  return "in_force";
}

/** The look of a control price's state: in force is issued, a future one a draft, an ended one void. */
export function controlPriceChip(state: "future" | "in_force" | "ended"): ChipState {
  switch (state) {
    case "in_force":
      return "issued";
    case "future":
      return "draft";
    default:
      return "void";
  }
}

/** Today as the browser's calendar date, ISO (yyyy-mm-dd): the default of a date field, never a rule. */
export function isoToday(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}
