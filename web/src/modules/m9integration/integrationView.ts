// The small pure helpers of the integration screens, kept apart so that they are tested without a
// browser (integrationView.test.ts).

import { ApiProblem } from "../../shell/api/client";
import { startOfMonthBefore } from "../../shell/i18n/formats";

export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/**
 * The period the export form opens with: the first day of the month two months back to today,
 * which covers the demo's eight weeks of trading, `today` being the business day
 * (businessToday(), Asia/Colombo). A starting value for the form only; the server decides what
 * a period may be.
 */
export function defaultExportPeriod(today: string): { from: string; to: string } {
  return { from: startOfMonthBefore(today, 2), to: today };
}

/** journal_2026-09-01_2026-09-30.csv: the name the browser saves the file under. */
export function journalFileName(periodFrom: string, periodTo: string): string {
  return `journal_${periodFrom}_${periodTo}.csv`;
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
