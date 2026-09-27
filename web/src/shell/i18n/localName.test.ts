import { describe, expect, it } from "vitest";
import { entityText, inLocale, locationText, skuText } from "./localName";

const RICE = { skuCode: "SKU-DCTEF6PA", nameEn: "Samba rice 5 kg", nameSi: "සම්බා සහල් 5 kg", nameTa: "சம்பா அரிசி 5 kg" };

describe("a name held in three languages", () => {
  it("is shown in the reader's language", () => {
    expect(inLocale("si", "Rice", "සහල්", "அரிசி")).toBe("සහල්");
    expect(inLocale("ta", "Rice", "සහල්", "அரிசி")).toBe("அரிசி");
    expect(inLocale("en", "Rice", "සහල්", "அரிசி")).toBe("Rice");
  });

  it("falls back to English when the reader's language has none", () => {
    expect(inLocale("si", "Rice", null, null)).toBe("Rice");
    expect(inLocale("ta", "Rice", "සහල්", "")).toBe("Rice");
  });

  it("names an item by its code and the reader's name, as the order line shows it", () => {
    expect(skuText(RICE, "si")).toBe("SKU-DCTEF6PA සම්බා සහල් 5 kg");
    expect(skuText(RICE, "ta")).toBe("SKU-DCTEF6PA சம்பா அரிசி 5 kg");
    expect(skuText(RICE, "en")).toBe("SKU-DCTEF6PA Samba rice 5 kg");
  });

  it("names a party by its code and legal name, and never writes null for a missing code", () => {
    const federation = { legalNameEn: "Cooperative Federation", legalNameSi: "සමුපකාර සම්මේලනය" };
    expect(entityText({ ...federation, entityCode: "FED" }, "si")).toBe("FED සමුපකාර සම්මේලනය");
    expect(entityText({ ...federation, entityCode: null }, "si")).toBe("සමුපකාර සම්මේලනය");
    expect(entityText(federation, "en")).toBe("Cooperative Federation");
  });

  it("names a location by its code and the reader's name", () => {
    expect(locationText({ locationCode: "W01", nameEn: "Kurunegala warehouse", nameSi: "කුරුණෑගල ගබඩාව" }, "si")).toBe(
      "W01 කුරුණෑගල ගබඩාව"
    );
  });
});
