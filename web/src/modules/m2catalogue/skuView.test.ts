import { describe, expect, it } from "vitest";
import messages from "./catalogue.messages.json" with { type: "json" };
import type { Sku } from "./catalogueApi";
import { chipOf, EMPTY_FORM, formOf, keyForUpload, languageOf, nameIn, requestOf } from "./skuView";

describe("the key of an image upload", () => {
  function fakeKey() {
    let n = 1;
    return { current: () => `key-${n}`, next: () => void n++ };
  }

  it("stays the same for a retry of the same file, and changes for a different file after a failed upload", () => {
    const key = fakeKey();
    const lastSent = { current: null as string | null };

    expect(keyForUpload(key, lastSent, "hash-a|")).toBe("key-1");
    // The storage PUT of a.jpg failed: the retry of a.jpg renews the same PENDING row.
    expect(keyForUpload(key, lastSent, "hash-a|")).toBe("key-1");
    // The user picks b.jpg instead: a new action, a new key.
    expect(keyForUpload(key, lastSent, "hash-b|")).toBe("key-2");
    // The same file with another barcode is another body too.
    expect(keyForUpload(key, lastSent, "hash-b|4790000000001")).toBe("key-3");
  });
});

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
