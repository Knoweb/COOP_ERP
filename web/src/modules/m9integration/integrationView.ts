// The small pure helpers of the integration screens, kept apart so that they are tested without a
// browser (integrationView.test.ts).

import { ApiProblem } from "../../shell/api/client";

export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/** A calendar date as the API writes it (2026-09-27), from a date's local day. */
export function isoDay(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * The period the export form opens with: the first day of the month two months back to today,
 * which covers the demo's eight weeks of trading. A starting value for the form only; the
 * server decides what a period may be.
 */
export function defaultExportPeriod(today: Date): { from: string; to: string } {
  const start = new Date(today.getFullYear(), today.getMonth() - 2, 1);
  return { from: isoDay(start), to: isoDay(today) };
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
