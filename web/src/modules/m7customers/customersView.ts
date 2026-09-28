// What the customer screens compute from the API's data, kept out of the components so it is
// tested on its own: the name in the reader's language, the problem an error carries, and dates.

import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";

const BUSINESS_TIME_ZONE = "Asia/Colombo";

/** The problem code the server answers when a recent holder of the phone must be confirmed as another person. */
export const PHONE_REUSE_CONFIRM = "m7.customer.phone_reuse_confirm";

type Named = { displayName: string; displayNameSi?: string | null; displayNameTa?: string | null };

/** The customer's name in the reader's language, and whether it fell back to the English one. */
export function nameIn(locale: string, customer: Named): { text: string; isFallback: boolean } {
  const translated = locale === "si" ? customer.displayNameSi : locale === "ta" ? customer.displayNameTa : customer.displayName;
  return translated ? { text: translated, isFallback: false } : { text: customer.displayName, isFallback: locale !== "en" };
}

/** The translated title of the server's refusal, or the fallback for anything else. */
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

export function problemCode(error: unknown): string | undefined {
  return error instanceof ApiProblem ? error.problem.code : undefined;
}

/** The look of an account's or a customer's status. */
export function statusChip(status: string): ChipState {
  return status === "OPEN" || status === "ACTIVE" ? "issued" : status === "SUSPENDED" ? "disputed" : "void";
}

/** How much of the limit the balance uses, in whole per cent (0 when there is no limit). */
export function limitUsedPercent(creditLimit: number, balance: number): number {
  if (creditLimit <= 0) {
    return 0;
  }
  return Math.floor((Math.max(balance, 0) * 100) / creditLimit);
}

/** Today's business date (Asia/Colombo): yyyy-mm-dd. */
export function businessToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: BUSINESS_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const part = (type: string) => parts.find((each) => each.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

/** The first day of the month `months` before the month of `day` (yyyy-mm-dd): a statement's default start. */
export function startOfMonthBefore(day: string, months: number): string {
  const [year, month] = day.split("-").map(Number);
  const index = year * 12 + (month - 1) - months;
  const y = Math.floor(index / 12);
  const m = (index % 12) + 1;
  return `${y}-${String(m).padStart(2, "0")}-01`;
}
