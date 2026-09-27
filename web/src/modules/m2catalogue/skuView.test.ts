import { describe, expect, it } from "vitest";
import messages from "./catalogue.messages.json" with { type: "json" };
import type { Sku } from "./catalogueApi";
import { chipOf, EMPTY_FORM, formOf, languageOf, nameIn, requestOf } from "./skuView";

const SKU: Sku = {
  skuId: "0190e620-0000-7000-8000-000000000001",
  skuCode: "SKU-1",
  ownerEntityId: "0190e620-0000-7000-8000-000000000002",
  status: "LOCAL",
  nameEn: "Rice",
  nameSi: "සහල්",
  baseUomCode: "KG",
  soldByWeight: true,
  batchTracked: false,
  expiryTracked: false,
  hasPrintedMrp: false,
  taxCategoryId: "0190e620-0000-7000-8000-000000000100",
  multiMrpPolicy: "PICKER",
  originKind: "REPACK_OUTPUT",
  expiryWarningDays: 7
};

describe("the SKU view", () => {
  it("shows a draft as a draft, LOCAL and SHARED as in use, INACTIVE as void", () => {
    expect(chipOf("DRAFT")).toBe("draft");
    expect(chipOf("LOCAL")).toBe("issued");
    expect(chipOf("SHARED")).toBe("issued");
    expect(chipOf("INACTIVE")).toBe("void");
  });

  it("names the item in the reader's language and falls back to English", () => {
    expect(nameIn(SKU, "si")).toBe("සහල්");
    expect(nameIn(SKU, "ta")).toBe("Rice");
    expect(nameIn(SKU, "en")).toBe("Rice");
    expect(languageOf("ta")).toBe("ta");
    expect(languageOf("fr")).toBe("en");
  });

  it("keeps the fields the editor does not show on an update and uses the defaults on a create", () => {
    const update = requestOf({ ...formOf(SKU), nameEn: " Samba rice " }, SKU);
    expect(update.nameEn).toBe("Samba rice");
    expect(update.nameTa).toBeUndefined();
    expect(update.multiMrpPolicy).toBe("PICKER");
    expect(update.originKind).toBe("REPACK_OUTPUT");
    expect(update.expiryWarningDays).toBe(7);

    const create = requestOf({ ...EMPTY_FORM, nameEn: "Dhal", taxCategoryId: SKU.taxCategoryId });
    expect(create.multiMrpPolicy).toBe("AUTO_LOWEST");
    expect(create.originKind).toBe("PURCHASED");
    expect(create.baseUomCode).toBe("EA");
  });

  it("has a text for every state in every language", () => {
    for (const locale of ["en", "si", "ta"] as const) {
      for (const status of ["DRAFT", "LOCAL", "SHARED", "INACTIVE"]) {
        expect(messages[locale][`catalogue.status.${status}` as keyof (typeof messages)["en"]]).toBeTruthy();
      }
    }
  });
});
