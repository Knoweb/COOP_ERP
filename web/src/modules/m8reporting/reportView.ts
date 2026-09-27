import { ApiProblem } from "../../shell/api/client";
import type { ReportDefinition, ReportQuery } from "./reportingApi";

/** The problem's title, which the server has already translated; the fallback otherwise. */
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
 * The period a period report opens with: the first of this month to today. A starting value
 * for the form only; the server decides what a period may be.
 */
export function defaultPeriod(today: Date): { from: string; to: string } {
  return { from: isoDay(new Date(today.getFullYear(), today.getMonth(), 1)), to: isoDay(today) };
}

/** What is sent for a report: only the parameters the definition takes, and no empty ones. */
export function queryOf(
  definition: Pick<ReportDefinition, "period" | "location">,
  form: { from: string; to: string; locationId: string }
): ReportQuery {
  const query: ReportQuery = {};
  if (definition.period) {
    query.from = form.from;
    query.to = form.to;
  }
  if (definition.location && form.locationId) {
    query.locationId = form.locationId;
  }
  return query;
}

/** The file name of a CSV download, as the server names it. */
export function csvFileName(reportId: string, query: ReportQuery): string {
  return query.from ? `${reportId}_${query.from}_${query.to}.csv` : `${reportId}.csv`;
}

/** Whether a column is a number, right-aligned. */
export function isNumeric(kind: string): boolean {
  return kind === "QTY" || kind === "MONEY";
}
