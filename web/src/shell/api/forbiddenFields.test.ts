import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ALLOWED_FIELDS, FORBIDDEN_WORDS, SECRET_WORDS, hasForbiddenField, isForbiddenField } from "./forbiddenFields";

// The Java rule this file is a copy of. The test runs from web/, so the repository root is one up.
const OUTBOX_WRITER = resolve(
  process.cwd(),
  "../backend/app/src/main/java/lk/coopfed/knoweb/kernel/internal/event/OutboxWriter.java"
);

/** The strings of `Set.of("a", "b", ...)` that follows `name =` in the Java source, as a sorted list. */
function javaSet(source: string, name: string): string[] {
  const match = new RegExp(String.raw`${name}\s*=\s*Set\.of\(([^;]*?)\);`, "s").exec(source);
  if (!match) {
    throw new Error(`${name} is not found in OutboxWriter.java; the pin test needs updating`);
  }
  return [...match[1].matchAll(/"([^"]*)"/g)].map((word) => word[1]).sort();
}

describe("the forbidden-field rule", () => {
  const java = readFileSync(OUTBOX_WRITER, "utf8");

  it("is pinned to the kernel's word lists (OutboxWriter.java): change both or neither", () => {
    expect([...FORBIDDEN_WORDS].sort(), "FORBIDDEN_WORDS").toEqual(javaSet(java, "FORBIDDEN_WORDS"));
    expect([...ALLOWED_FIELDS].sort(), "ALLOWED_FIELDS").toEqual(javaSet(java, "ALLOWED_FIELDS"));
    expect([...SECRET_WORDS].sort(), "SECRET_WORDS").toEqual(javaSet(java, "SECRET_WORDS"));
  });

  it("refuses the names of personal and secret data, as whole words", () => {
    for (const key of ["nic", "phone", "mobile_no", "customerName", "emailAddress", "password", "pinCode", "tokenId", "dateofbirth"]) {
      expect(isForbiddenField(key), key).toBe(true);
    }
  });

  it("accepts identifiers, codes and words that only contain a forbidden one", () => {
    for (const key of ["reasonCode", "reasonText", "entityId", "addressId", "cityCode", "technicianId", "shippingId", "nameEn", "quantity"]) {
      expect(isForbiddenField(key), key).toBe(false);
    }
  });

  it("looks at every depth of a JSON body, arrays included", () => {
    expect(hasForbiddenField({ reasonCode: "X", reasonText: "counted again" })).toBe(false);
    expect(hasForbiddenField({ lines: [{ skuId: "a", quantity: 2 }] })).toBe(false);
    expect(hasForbiddenField({ party: { nic: "x" } })).toBe(true);
    expect(hasForbiddenField({ lines: [{ contactPhone: "x" }] })).toBe(true);
    expect(hasForbiddenField("a string")).toBe(false);
    expect(hasForbiddenField(null)).toBe(false);
  });
});
