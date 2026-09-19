// How the web client shows a point in time.
//
// The API sends instants in UTC (ISO-8601, ending in Z). The screen is the only place where an
// instant becomes wall-clock time, and it always uses the business time zone, not the zone
// of the user's computer: a manager travelling abroad must read the same times as the shop.
// Never add or subtract an offset by hand; give the zone to the formatter.

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
