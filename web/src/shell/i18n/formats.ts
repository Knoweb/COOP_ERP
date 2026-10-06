// How the web client shows a point in time and an amount of money (doc 30 section 3: "dates and
// money formatted centrally"). A screen never formats either by itself.
//
// TIME. The API sends instants in UTC (ISO-8601, ending in Z). The screen is the only place where
// an instant becomes wall-clock time, and it always uses the business time zone, not the zone
// of the user's computer: a manager travelling abroad must read the same times as the shop.
// Never add or subtract an offset by hand; give the zone to the formatter.
//
// MONEY. See formatMoneyDigits below; the component that shows it is shell/components/MoneyDisplay.

export const BUSINESS_TIME_ZONE = "Asia/Colombo";

// Doc 19 section 5.1: "Numerals and dates ... Western Arabic digits (0-9) in every language,
// including Sinhala and Tamil interfaces; dates dd/mm/yyyy ... ICU locale data for si-LK and
// ta-LK would default to native numerals for some formats; the platform overrides this once,
// centrally" (DR-6). The kernel's IcuFormats (kernel/internal/i18n) answers this the same way for
// the PDF: one fixed `dd/MM/yyyy` shape, taken from the LATN symbols of en-LK, never from the
// caller's locale. The screen must match it, and not only for the digits: si-LK's CLDR data
// gives the Gregorian calendar's own month names as the *traditional* Sinhala lunar months
// (Bak, ... Binara, Vap, ...; confirmed on Node's ICU 78), which is not what a Sri Lankan
// business document means by "September" (the design review of 28 September 2026, and the demo
// screens showing "2026 බිනර 28" for 28 September). Formatting through `Intl.DateTimeFormat`
// with only numeric fields, LATN digits and the Gregorian calendar avoids the month-name table
// entirely; the shape is then assembled by hand so it does not vary with the locale's own field
// order either (si's numeric order is yyyy-MM-dd, not dd/MM/yyyy).
const DATE_PARTS: Intl.DateTimeFormatOptions = {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  calendar: "gregory",
  numberingSystem: "latn"
};

function partsOf(date: Date, options: Intl.DateTimeFormatOptions): Record<string, string> {
  const parts = new Intl.DateTimeFormat("en", options).formatToParts(date);
  return Object.fromEntries(parts.map((part) => [part.type, part.value]));
}

/** Returns a function that formats an API instant as `dd/MM/yyyy HH:mm` in the business zone. */
export function useFormatInstant() {
  return (instant: string) => {
    const date = new Date(instant);
    if (Number.isNaN(date.getTime())) {
      return instant;
    }
    const parts = partsOf(date, {
      ...DATE_PARTS,
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZone: BUSINESS_TIME_ZONE
    });
    return `${parts.day}/${parts.month}/${parts.year} ${parts.hour}:${parts.minute}`;
  };
}

// What a calendar date from the API looks like (an OpenAPI `format: date`): 2026-09-25.
const CALENDAR_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Returns a function that formats a CALENDAR DATE from the API (2026-09-25, no time, no zone)
 * as `dd/MM/yyyy`, and nothing more. A calendar date is not an instant: it was signed, born or
 * due on that day everywhere, so no time zone may move it. It is read as a day and formatted in
 * UTC, so that 2026-09-25 prints as 25/09/2026 and never as a time nobody recorded
 * (useFormatInstant read it as UTC midnight and printed 5:30 AM in Colombo; the review of
 * 26 September). A text that is not a calendar date is returned as it came, so a wrong field
 * shows what the server sent instead of "Invalid Date".
 */
export function useFormatDate() {
  return (date: string) => {
    if (!CALENDAR_DATE.test(date)) {
      return date;
    }
    const parts = partsOf(new Date(`${date}T00:00:00Z`), { ...DATE_PARTS, timeZone: "UTC" });
    return `${parts.day}/${parts.month}/${parts.year}`;
  };
}

/**
 * Today's business date (Asia/Colombo) as the API writes a calendar date: yyyy-mm-dd. The one
 * place a screen asks "what is today": a manager abroad, or a computer set to another zone,
 * still reads the shop's day. `now` is a parameter so that a test names the instant and never
 * depends on the clock. Built from the parts, not from a locale's date format ("en-CA" writes
 * yyyy-mm-dd): where the date formatter is the FormatJS polyfill (localeData.ts) only en, si
 * and ta exist.
 */
export function businessToday(now: Date = new Date()): string {
  const parts = partsOf(now, { ...DATE_PARTS, timeZone: BUSINESS_TIME_ZONE });
  return `${parts.year}-${parts.month}-${parts.day}`;
}

/**
 * The first day of the month `months` before the month of `day` (yyyy-mm-dd), as yyyy-mm-dd.
 * Text arithmetic on the calendar date, so no zone can move it: with 0 it is the first of the
 * month of `day`.
 */
export function startOfMonthBefore(day: string, months: number): string {
  const [year, month] = day.split("-").map(Number);
  const index = year * 12 + (month - 1) - months;
  const y = Math.floor(index / 12);
  const m = (index % 12) + 1;
  return `${y}-${String(m).padStart(2, "0")}-01`;
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
