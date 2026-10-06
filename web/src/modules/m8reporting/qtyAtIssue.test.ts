import { createIntl } from "react-intl";
import { describe, expect, it } from "vitest";
import { messages, type Locale } from "../../shell/i18n/messages";

// The exception queue showed "1.000 units at issue": the API sends numeric(14,3) as text.
// ExceptionList passes the quantity as a number, so the message drops the trailing zeros and
// English says "unit" for one.
function qtyAtIssue(locale: Locale, amount: string): string {
  const intl = createIntl({ locale, messages: messages[locale] });
  return intl.formatMessage({ id: "reporting.exception.qty_at_issue" }, { qty: Number(amount) });
}

describe("quantity at issue", () => {
  it("has no trailing zeros and a plural in English", () => {
    expect(qtyAtIssue("en", "1.000")).toBe("1 unit at issue");
    expect(qtyAtIssue("en", "3.000")).toBe("3 units at issue");
    expect(qtyAtIssue("en", "2.500")).toBe("2.5 units at issue");
  });

  it("reads in Sinhala and Tamil", () => {
    expect(qtyAtIssue("si", "3.000")).toBe("ඒකක 3 ක් ගැටලුවට ලක්ව ඇත");
    expect(qtyAtIssue("ta", "1.000")).toBe("1 அலகு சிக்கலில்");
    expect(qtyAtIssue("ta", "3.000")).toBe("3 அலகுகள் சிக்கலில்");
  });
});
