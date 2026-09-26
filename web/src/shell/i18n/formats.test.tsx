import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { IntlProvider } from "react-intl";
import { describe, expect, it } from "vitest";
import { formatMoneyDigits, useFormatDate, useFormatInstant } from "./formats";
import { messages } from "./messages";

const inEnglish = ({ children }: { children: ReactNode }) => (
  <IntlProvider locale="en" messages={messages.en}>
    {children}
  </IntlProvider>
);

describe("formatting a calendar date", () => {
  it("prints the day the API sent and no time, whatever the zone: a date is not an instant", () => {
    const { result } = renderHook(() => useFormatDate(), { wrapper: inEnglish });
    // 2026-09-25 read as UTC midnight is 5:30 AM in Colombo; a signature has no such time.
    expect(result.current("2026-09-25")).toBe("Sep 25, 2026");
    expect(result.current("2026-01-01")).toBe("Jan 1, 2026");
  });

  it("differs from an instant, which is a date AND a time in the business zone", () => {
    const { result } = renderHook(() => useFormatInstant(), { wrapper: inEnglish });
    expect(result.current("2026-09-25T00:00:00Z")).toContain("5:30");
  });

  it("shows a text that is not a calendar date as it came, never 'Invalid Date'", () => {
    const { result } = renderHook(() => useFormatDate(), { wrapper: inEnglish });
    expect(result.current("25/09/2026")).toBe("25/09/2026");
    expect(result.current("")).toBe("");
  });
});

describe("formatting an amount of money", () => {
  it("shows at least two decimals, because a JSON number loses its zeros on the way", () => {
    expect(formatMoneyDigits("0")?.digits).toBe("0.00");
    expect(formatMoneyDigits("0.5")?.digits).toBe("0.50");
    expect(formatMoneyDigits("12")?.digits).toBe("12.00");
    expect(formatMoneyDigits(1234.5)?.digits).toBe("1,234.50");
  });

  it("puts a comma before every group of three digits, the western way and not the lakh way", () => {
    expect(formatMoneyDigits("999.00")?.digits).toBe("999.00");
    expect(formatMoneyDigits("1000.00")?.digits).toBe("1,000.00");
    expect(formatMoneyDigits("1234567.00")?.digits).toBe("1,234,567.00");
    // The largest amount numeric(14,2) can hold.
    expect(formatMoneyDigits("999999999999.99")?.digits).toBe("999,999,999,999.99");
  });

  it("never rounds and never cuts: more than two decimals are shown exactly as they arrived", () => {
    expect(formatMoneyDigits("1234567.891")?.digits).toBe("1,234,567.891");
    expect(formatMoneyDigits("12.3450")?.digits).toBe("12.3450");
    // 1.005 is the classic: float rounding makes 1.00 of it, correct rounding 1.01. The client does neither.
    expect(formatMoneyDigits("1.005")?.digits).toBe("1.005");
  });

  it("keeps every digit of an amount too large for a JavaScript number, because it never converts it", () => {
    expect(formatMoneyDigits("123456789012345678901234.56")?.digits).toBe("123,456,789,012,345,678,901,234.56");
  });

  it("reports a negative amount as negative and leaves the sign out of the digits", () => {
    expect(formatMoneyDigits("-1234.5")).toEqual({ digits: "1,234.50", negative: true });
    expect(formatMoneyDigits(-0.5)).toEqual({ digits: "0.50", negative: true });
    expect(formatMoneyDigits("+15.00")).toEqual({ digits: "15.00", negative: false });
  });

  it("shows zero without a sign, however it was sent", () => {
    expect(formatMoneyDigits("-0.00")).toEqual({ digits: "0.00", negative: false });
    expect(formatMoneyDigits(-0)).toEqual({ digits: "0.00", negative: false });
  });

  it("drops leading zeros and the spaces around the amount, which carry no value", () => {
    expect(formatMoneyDigits("007.10")?.digits).toBe("7.10");
    expect(formatMoneyDigits(" 42.00 ")?.digits).toBe("42.00");
    expect(formatMoneyDigits("000.50")?.digits).toBe("0.50");
  });

  it("answers null, never NaN and never an exception, for anything that is not a plain decimal", () => {
    const notAmounts = ["", "abc", "12.", ".5", "1,234.00", "1e3", "12.3.4", "Rs 5", "--5", "١٢٣", NaN, Infinity, -Infinity, 1e21, null, undefined];
    for (const value of notAmounts) {
      expect(formatMoneyDigits(value), String(value)).toBeNull();
    }
  });

  it("does not crash on a value the types did not promise, as JSON can always deliver one", () => {
    expect(formatMoneyDigits({} as unknown as string)).toBeNull();
    expect(formatMoneyDigits(true as unknown as number)).toBeNull();
  });
});
