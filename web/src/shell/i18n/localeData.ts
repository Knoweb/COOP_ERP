// The number and date data of Sinhala and Tamil for a browser that lacks them.
//
// react-intl formats through the browser's Intl.NumberFormat and Intl.DateTimeFormat. Chrome
// and Firefox carry Sinhala and Tamil, but a browser built with a reduced ICU (an embedded
// Chromium, some kiosks) may not: react-intl then logs "MissingDataError: Missing locale data
// for locale si" and formats in English. Where either language is missing, this replaces the
// two formatters with the FormatJS polyfills and loads their data for en, si and ta, so every
// language formats the same way everywhere. A browser that has both loads nothing.
//
// Money is not affected: it never goes through Intl (formats.ts, formatMoneyDigits).

import { shouldPolyfill as numberFormatMissing } from "@formatjs/intl-numberformat/should-polyfill.js";
import { shouldPolyfill as dateTimeFormatMissing } from "@formatjs/intl-datetimeformat/should-polyfill.js";

const LANGUAGES = ["si", "ta"] as const;

/** The languages whose number or date data this browser lacks; empty when it has them all. */
export function missingLocaleData(): { numbers: boolean; dates: boolean } {
  return {
    numbers: LANGUAGES.some((language) => numberFormatMissing(language) !== undefined),
    dates: LANGUAGES.some((language) => dateTimeFormatMissing(language) !== undefined)
  };
}

/** Loads the polyfills a browser needs; resolves at once when it needs none. Never rejects. */
export async function ensureLocaleData(): Promise<void> {
  const missing = missingLocaleData();
  try {
    if (missing.numbers) {
      await import("@formatjs/intl-numberformat/polyfill-force.js");
      await Promise.all([
        import("@formatjs/intl-numberformat/locale-data/en.js"),
        import("@formatjs/intl-numberformat/locale-data/si.js"),
        import("@formatjs/intl-numberformat/locale-data/ta.js")
      ]);
    }
    if (missing.dates) {
      await import("@formatjs/intl-datetimeformat/polyfill-force.js");
      await Promise.all([
        import("@formatjs/intl-datetimeformat/locale-data/en.js"),
        import("@formatjs/intl-datetimeformat/locale-data/si.js"),
        import("@formatjs/intl-datetimeformat/locale-data/ta.js"),
        // The business time zone (formats.ts) needs its rules in the polyfill.
        import("@formatjs/intl-datetimeformat/add-golden-tz.js")
      ]);
    }
  } catch (error) {
    // Without the data the screens still work, in English number and date formats.
    console.warn("Could not load the Sinhala and Tamil number and date data", error);
  }
}
