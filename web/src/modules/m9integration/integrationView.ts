// The small pure helpers of the integration screens, kept apart so that they are tested without a
// browser (integrationView.test.ts).

import { ApiProblem } from "../../shell/api/client";
import { startOfMonthBefore } from "../../shell/i18n/formats";

export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/**
 * The period the export form opens with: the first day of the month two months back to yesterday,
 * which covers the demo's eight weeks of trading and ends on a closed day, `today` being the
 * business day (businessToday(), Asia/Colombo). Yesterday, not today: a period that ends today is
 * still open, and the server refuses it unless the export is marked provisional (wave 2, CR-29-1).
 * A starting value for the form only; the server decides what a period may be.
 */
export function defaultExportPeriod(today: string): { from: string; to: string } {
  return { from: startOfMonthBefore(today, 2), to: dayBefore(today) };
}

/** The ISO date one day before an ISO date, in the calendar alone (no time zone, no clock). */
export function dayBefore(isoDate: string): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
}

/**
 * Whether a period ending on `to` is still open on the business day `today`: the day has not
 * ended, so an export of it is provisional by the server's rule (periodTo >= today). ISO dates
 * compare as text.
 */
export function periodIsOpen(to: string, today: string): boolean {
  return to !== "" && to >= today;
}

/**
 * journal_2026-09-01_2026-09-30.csv: the name the browser saves the file under;
 * journal_2026-09-01_2026-09-30_PROVISIONAL.csv when the export was made before its period closed.
 */
export function journalFileName(periodFrom: string, periodTo: string, provisional = false): string {
  return `journal_${periodFrom}_${periodTo}${provisional ? "_PROVISIONAL" : ""}.csv`;
}

/** The text of a template in a language, English when that language has none (P-07). */
export function templateText(
  en: string | null | undefined,
  si: string | null | undefined,
  ta: string | null | undefined,
  language: "en" | "si" | "ta"
): { text: string; fallback: boolean } {
  const chosen = language === "si" ? si : language === "ta" ? ta : en;
  if (chosen) {
    return { text: chosen, fallback: false };
  }
  return { text: en ?? "", fallback: language !== "en" };
}
