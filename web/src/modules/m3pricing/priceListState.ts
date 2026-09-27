import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";
import type { PriceList } from "./pricingApi";

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
