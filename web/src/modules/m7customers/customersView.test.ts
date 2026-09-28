import { describe, expect, it } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { businessToday, limitUsedPercent, nameIn, problemCode, startOfMonthBefore, statusChip } from "./customersView";

describe("the customer view helpers", () => {
  it("names the customer in the reader's language, falling back to English with the tag", () => {
    const customer = { displayName: "K. Perera", displayNameSi: "කේ. පෙරේරා", displayNameTa: null };
    expect(nameIn("si", customer)).toEqual({ text: "කේ. පෙරේරා", isFallback: false });
    expect(nameIn("ta", customer)).toEqual({ text: "K. Perera", isFallback: true });
    expect(nameIn("en", customer)).toEqual({ text: "K. Perera", isFallback: false });
  });

  it("reads the problem code and the share of the limit used", () => {
    expect(problemCode(new ApiProblem({ status: 422, code: "m7.customer.phone_reuse_confirm" }))).toBe(
      "m7.customer.phone_reuse_confirm"
    );
    expect(problemCode(new Error("x"))).toBeUndefined();
    expect(limitUsedPercent(15000, 14200)).toBe(94);
    expect(limitUsedPercent(0, 100)).toBe(0);
    expect(limitUsedPercent(1000, -50)).toBe(0);
    expect(statusChip("OPEN")).toBe("issued");
    expect(statusChip("CLOSED")).toBe("void");
  });

  it("dates in the business time zone, and the start of a month before", () => {
    // 20:00 UTC on the 28th is already the 29th in Colombo (UTC+5:30).
    expect(businessToday(new Date("2026-09-28T20:00:00Z"))).toBe("2026-09-29");
    expect(startOfMonthBefore("2026-09-29", 2)).toBe("2026-07-01");
    expect(startOfMonthBefore("2026-01-15", 2)).toBe("2025-11-01");
  });
});
