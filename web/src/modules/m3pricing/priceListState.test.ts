import { describe, expect, it } from "vitest";
import messages from "./pricing.messages.json" with { type: "json" };
import { bindingCeiling, chipOf, controlPriceChip, controlPriceState, incompleteLines, reasonMessageId } from "./priceListState";
import type { ControlPrice, LineOutcome } from "./pricingApi";

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
  "m3.price_list.review.above_mrp",
  // M3-06: the shelf list's ceilings
  "m3.price_list.line.above_control_price",
  "m3.price_list.line.above_shelf_mrp",
  "m3.price_list.line.retail_precision",
  "m3.price_list.line.tier_not_allowed"
];

// The enums of the slice the screens turn into message ids: every value needs its text.
const ENUM_IDS = [
  ...["none", "mrp_lowest", "mrp_barcode", "mrp_picked", "control_price"].map((value) => `pricing.cap.${value}`),
  ...["auto_lowest", "barcode_resolved", "picker"].map((value) => `pricing.policy.${value}`),
  ...["own", "federation", "default"].map((value) => `pricing.policy.source.${value}`),
  ...["future", "in_force", "ended"].map((value) => `pricing.gazette.state.${value}`),
  ...["trade", "retail", "advisory"].map((value) => `pricing.kind.${value}`),
  ...["control_price", "mrp"].map((value) => `pricing.ceiling.${value}`)
];

const control = (effectiveFrom: string, effectiveTo?: string): ControlPrice => ({
  controlPriceId: "cp",
  skuId: "sku",
  ceilingPrice: 220,
  ceilingUomCode: "EA",
  effectiveFrom,
  effectiveTo: effectiveTo ?? null,
  gazetteReference: "2492/29",
  enteredAt: "2026-09-01T00:00:00Z"
});

describe("price list state", () => {
  it("shows a draft as a draft, a published version as issued, the rest as void", () => {
    expect(chipOf("DRAFT")).toBe("draft");
    expect(chipOf("PUBLISHED")).toBe("issued");
    expect(chipOf("SUPERSEDED")).toBe("void");
    expect(chipOf("WITHDRAWN")).toBe("void");
  });

  it("has a text for every line reason and every enum value in every language", () => {
    for (const id of [...REASONS.map(reasonMessageId), ...ENUM_IDS]) {
      for (const language of ["en", "si", "ta"] as const) {
        expect(Object.keys(messages[language]), `${language} ${id}`).toContain(id);
      }
    }
  });

  it("reads the binding ceiling of a line, and none when the server named none", () => {
    const refused: LineOutcome = {
      skuId: "sku",
      uomCode: "EA",
      tierFromQty: 0,
      ok: false,
      reason: "m3.price_list.line.above_control_price",
      ceilingKind: "CONTROL_PRICE",
      ceilingValue: 220,
      ceilingRef: "2492/29"
    };
    expect(bindingCeiling(refused)).toEqual({ kind: "CONTROL_PRICE", value: 220, ref: "2492/29" });
    expect(bindingCeiling({ ...refused, ceilingKind: "MRP", ceilingRef: null })).toEqual({ kind: "MRP", value: 220, ref: "" });
    expect(bindingCeiling({ ...refused, ceilingKind: null, ceilingValue: null })).toBeNull();
    expect(bindingCeiling(undefined)).toBeNull();
  });

  it("places a control price before, in or after its dates, the last day included", () => {
    expect(controlPriceState(control("2026-10-01"), "2026-09-30")).toBe("future");
    expect(controlPriceState(control("2026-10-01"), "2026-10-01")).toBe("in_force");
    expect(controlPriceState(control("2026-10-01", "2026-10-31"), "2026-10-31")).toBe("in_force");
    expect(controlPriceState(control("2026-10-01", "2026-10-31"), "2026-11-01")).toBe("ended");
    expect(controlPriceChip("in_force")).toBe("issued");
    expect(controlPriceChip("future")).toBe("draft");
    expect(controlPriceChip("ended")).toBe("void");
  });

  it("names the shelf lines whose price is blank or not a plain decimal as incomplete", () => {
    expect(incompleteLines([{ price: "12.50" }, { price: "" }, { price: "  " }, { price: "1e3" }, { price: "0" }, { price: "7" }])).toEqual([2, 3, 4]);
    expect(incompleteLines([])).toEqual([]);
  });
});
