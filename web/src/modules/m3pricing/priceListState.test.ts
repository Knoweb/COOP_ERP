import { describe, expect, it } from "vitest";
import messages from "./pricing.messages.json" with { type: "json" };
import { chipOf, reasonMessageId } from "./priceListState";

// The reasons SetLines answers with (openapi/m3pricing.yaml, setLines): each must have a text on
// the screen in every language, or a refused line would show its message id.
const REASONS = [
  "m3.price_list.line.incomplete",
  "m3.price_list.line.price_negative",
  "m3.price_list.line.price_precision",
  "m3.price_list.line.tier_invalid",
  "m3.price_list.line.sku_not_active",
  "m3.price_list.line.uom_invalid",
  "m3.price_list.line.duplicate",
  "m3.price_list.line.tier_base_missing",
  "m3.price_list.line.tiers_not_ascending",
  "m3.price_list.review.above_mrp"
];

describe("price list state", () => {
  it("shows a draft as a draft, a published version as issued, the rest as void", () => {
    expect(chipOf("DRAFT")).toBe("draft");
    expect(chipOf("PUBLISHED")).toBe("issued");
    expect(chipOf("SUPERSEDED")).toBe("void");
    expect(chipOf("WITHDRAWN")).toBe("void");
  });

  it("has a text for every line reason in every language", () => {
    for (const reason of REASONS) {
      const id = reasonMessageId(reason);
      for (const language of ["en", "si", "ta"] as const) {
        expect(Object.keys(messages[language]), `${language} ${id}`).toContain(id);
      }
    }
  });
});
