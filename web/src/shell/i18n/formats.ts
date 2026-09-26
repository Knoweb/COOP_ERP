// How the web client shows a point in time and an amount of money (doc 30 section 3: "dates and
// money formatted centrally"). A screen never formats either by itself.
//
// TIME. The API sends instants in UTC (ISO-8601, ending in Z). The screen is the only place where
// an instant becomes wall-clock time, and it always uses the business time zone, not the zone
// of the user's computer: a manager travelling abroad must read the same times as the shop.
// Never add or subtract an offset by hand; give the zone to the formatter.
//
// MONEY. See formatMoneyDigits below; the component that shows it is shell/components/MoneyDisplay.

import { useIntl } from "react-intl";

export const BUSINESS_TIME_ZONE = "Asia/Colombo";

/** Returns a function that formats an API instant as a date and time in the user's language. */
export function useFormatInstant() {
  const intl = useIntl();
  return (instant: string) =>
    intl.formatDate(instant, {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: BUSINESS_TIME_ZONE
    });
}

// What a calendar date from the API looks like (an OpenAPI `format: date`): 2026-09-25.
const CALENDAR_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Returns a function that formats a CALENDAR DATE from the API (2026-09-25, no time, no zone)
 * as a date in the user's language, and nothing more. A calendar date is not an instant: it
 * was signed, born or due on that day everywhere, so no time zone may move it. It is read as
 * a day and formatted in UTC, so that 2026-09-25 prints as 25 Sep 2026 and never as a time
 * nobody recorded (useFormatInstant read it as UTC midnight and printed 5:30 AM in Colombo;
 * the review of 26 September). A text that is not a calendar date is returned as it came,
 * so a wrong field shows what the server sent instead of "Invalid Date".
 */
export function useFormatDate() {
  const intl = useIntl();
  return (date: string) =>
    CALENDAR_DATE.test(date) ? intl.formatDate(`${date}T00:00:00Z`, { dateStyle: "medium", timeZone: "UTC" }) : date;
}

/** An amount ready to be shown: the digits without a sign ("1,234.00"), and whether it is below zero. */
export type MoneyDigits = { digits: string; negative: boolean };

// What an amount from the API looks like: an optional sign, digits, optionally a point and digits.
// No exponent, no spaces inside, no thousands separators, no currency.
const DECIMAL = /^([+-]?)(\d+)(?:\.(\d+))?$/;

/**
 * Turns an amount from the API into the digits the screen shows: "1234.5" becomes "1,234.50".
 * Returns null for anything that is not a plain decimal number; MoneyDisplay then shows a
 * placeholder. It never returns "NaN" and never throws.
 *
 * The rules, and why:
 *
 *  1. NO ARITHMETIC. The amount is handled as text from start to finish: split at the point,
 *     put commas into the whole part, pad the fraction. Money is BigDecimal with scale 2 on the
 *     server (17A section 4.4) and a JavaScript number is a binary fraction: 0.1 + 0.2 is
 *     0.30000000000000004, and 1.005 rounds to 1.00 because it is really 1.00499999999999989.
 *     A client that never calculates cannot be wrong by a cent. Totals, tax and change come from
 *     the server; do not add amounts up in a screen.
 *
 *  2. NEVER ROUND, NEVER CUT. The server is the authority for rounding. If more than two decimals
 *     arrive ("1234567.891", or a unit cost with scale 4 such as "12.3450"), they are shown exactly
 *     as they arrived. Rounding here would show a value the server never computed; cutting would
 *     hide that something upstream sent the wrong scale. An odd-looking amount on the screen gets
 *     reported and fixed; a silently tidied one is found at the audit.
 *
 *  3. AT LEAST TWO DECIMALS. "0.5" is shown as "0.50" and "12" as "12.00". Adding zeros at the end
 *     does not change the value; it is what a JSON number loses on the way (1234.50 arrives as
 *     1234.5).
 *
 *  4. WESTERN ARABIC DIGITS, COMMA FOR THOUSANDS, POINT FOR DECIMALS, in all three languages
 *     (doc 19 DR-6). That is why Intl.NumberFormat is not used: with a Tamil or Sinhala locale it
 *     may choose other digits or the lakh grouping (12,34,567), depending on the browser.
 *
 *  5. A STRING IS BETTER THAN A NUMBER. Both are accepted, because a slice may declare an amount
 *     either way. A number is turned into text with String(), which gives the shortest text that
 *     reads back as the same number, so nothing is invented. A number JavaScript writes with an
 *     exponent (1e21) is refused.
 *
 *  6. Zero has no sign: "-0.00" is shown as "0.00". It is the same value, and "minus nothing"
 *     only raises questions.
 */
export function formatMoneyDigits(amount: string | number | null | undefined): MoneyDigits | null {
  if (typeof amount === "number" && !Number.isFinite(amount)) {
    return null; // NaN, Infinity
  }
  if (typeof amount !== "string" && typeof amount !== "number") {
    return null; // null, undefined, or something the types did not promise
  }

  const match = DECIMAL.exec(String(amount).trim());
  if (!match) {
    return null;
  }
  const [, sign, wholeAsSent, fractionAsSent = ""] = match;

  // "007" is 7: leading zeros carry no value. One zero stays, so that 0.50 is not ".50".
  const whole = wholeAsSent.replace(/^0+(?=\d)/, "");
  // A comma before every group of three digits, counted from the right.
  const grouped = whole.replace(/\B(?=(\d{3})+$)/g, ",");
  const fraction = fractionAsSent.padEnd(2, "0");

  const isZero = /^0+$/.test(whole + fraction);
  return { digits: `${grouped}.${fraction}`, negative: sign === "-" && !isZero };
}
