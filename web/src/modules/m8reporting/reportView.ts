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
  return kind === "QTY" || kind === "MONEY" || kind === "COUNT" || kind === "PERCENT";
}

/** Where the user acts on an exception: the trading page of its subject, if it has one. */
export function exceptionLink(item: {
  kind: string;
  subjectId: string;
  role?: string;
  counterpartyEntityId?: string;
}): string | undefined {
  switch (item.kind) {
    case "DISCREPANCY_OPEN":
      return `/trading/discrepancies/${item.subjectId}`;
    case "INVOICE_DISPUTED":
      return `/trading/invoices/${item.subjectId}`;
    case "CHEQUE_BOUNCED":
      return `/trading/payments/${item.subjectId}`;
    case "EXPOSURE_WARNING":
      return item.role && item.counterpartyEntityId
        ? `/trading/accounts/${item.role}/${item.counterpartyEntityId}`
        : undefined;
    default:
      return undefined;
  }
}

/** Where a tile opens: its report, or the exception queue. */
export function tileLink(drillReportId: string | undefined): string | undefined {
  if (!drillReportId) {
    return undefined;
  }
  return drillReportId === "exceptions" ? "/reporting/exceptions" : `/reporting/reports/${drillReportId}`;
}

/** The heights of a trend's bars, 0 to 100, against its largest week; all 0 when every week is 0. */
export function trendHeights(values: string[]): number[] {
  const numbers = values.map((value) => Math.max(0, Number(value) || 0));
  const max = Math.max(0, ...numbers);
  return numbers.map((value) => (max === 0 ? 0 : Math.round((value / max) * 100)));
}
