// What the relationship screens compute without the server (21A section 8, the agreement sheet):
// the history of a pair, the row in force, and the credit-limit amendment built from what the
// clerk typed. Pure functions, tested in relationshipView.test.ts.

import type { ChipState } from "../../shell/components/StateChip";
import { BUSINESS_TIME_ZONE } from "../../shell/i18n/formats";
import type { AmendTermsRequest, Relationship } from "./partyApi";

/** Today's business date (Asia/Colombo), yyyy-mm-dd, as the server's guards read it. */
export function businessToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: BUSINESS_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

/** The look of a row's state: in force, being written, suspended, or replaced before it began. */
export function relationshipChip(row: Relationship, today: string): ChipState {
  if (row.status === "SUSPENDED") {
    return "alert";
  }
  if (row.status === "DRAFT") {
    return "draft";
  }
  if (row.status === "ACTIVE" && (!row.effectiveTo || row.effectiveTo >= today)) {
    return "issued";
  }
  return "void";
}

/** Every row of the same seller and buyer, the latest first: the timeline of 21A section 8. */
export function pairHistory(rows: Relationship[], of: Relationship): Relationship[] {
  return rows
    .filter((row) => row.sellerEntityId === of.sellerEntityId && row.buyerEntityId === of.buyerEntityId)
    .sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? 1 : a.effectiveFrom > b.effectiveFrom ? -1 : 0));
}

/** One row per buyer: the latest row of each pair (the one an amendment is made on). */
export function latestPerPair(rows: Relationship[]): Relationship[] {
  const latest = new Map<string, Relationship>();
  for (const row of rows) {
    if (row.status === "REPLACED") {
      continue;
    }
    const key = `${row.sellerEntityId}|${row.buyerEntityId}`;
    const seen = latest.get(key);
    if (!seen || row.effectiveFrom > seen.effectiveFrom) {
      latest.set(key, row);
    }
  }
  return [...latest.values()];
}

/** The day after `date` (yyyy-mm-dd). */
function nextDay(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/**
 * The first day a new limit may start (CR-21A-2): after the current row's first day and never
 * before today. Today, unless the row itself began today.
 */
export function firstLimitDate(current: Relationship, today: string): string {
  const afterStart = nextDay(current.effectiveFrom);
  return afterStart > today ? afterStart : today;
}

/** The limit typed is a number of rupees and cents, zero or more. */
export function limitReady(limit: string, effectiveFrom: string): boolean {
  const n = Number(limit);
  return limit.trim() !== "" && Number.isFinite(n) && n >= 0 && /^\d+(\.\d{1,2})?$/.test(limit.trim()) && effectiveFrom !== "";
}

/** AmendRelationshipTerms with the credit limit only: every other term keeps its value. */
export function limitRequest(limit: string, effectiveFrom: string, reasonCode: string, reasonText: string | null): AmendTermsRequest {
  return {
    effectiveFrom,
    creditLimit: Number(limit.trim()),
    reasonCode,
    reasonText: reasonText ?? undefined
  };
}

/** The reasons a credit limit is changed, offered by ReasonCapture (the audit record keeps the code). */
export const LIMIT_REASONS = ["REVIEW", "PAYMENT_RECORD", "REQUESTED", "OTHER"] as const;
