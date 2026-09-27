// The calls of the reporting module (M8), through the shell's API client and typed by the client
// generated from openapi/m8reporting.yaml. The locations of the stock position's filter come
// from M1's published list.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m8reporting";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";

export type Dashboard = components["schemas"]["DashboardResponse"];
export type Tile = components["schemas"]["DashboardTile"];
export type ReportDefinition = components["schemas"]["ReportDefinitionResponse"];
export type ReportData = components["schemas"]["ReportDataResponse"];
export type ReportRun = components["schemas"]["ReportRunResponse"];
export type Location = partyComponents["schemas"]["LocationResponse"];

/** The parameters of a report: a period for the period reports, a location for the stock position. */
export type ReportQuery = { from?: string; to?: string; locationId?: string };

export function useReportingApi() {
  const api = useApiClient<paths>();
  const party = useApiClient<partyPaths>();

  return useMemo(
    () => ({
      async dashboard(): Promise<Dashboard> {
        const { data } = await api.GET("/v1/reporting/dashboard");
        return data!;
      },

      async definitions(): Promise<ReportDefinition[]> {
        const { data } = await api.GET("/v1/reporting/reports");
        return data ?? [];
      },

      async data(reportId: string, query: ReportQuery): Promise<ReportData> {
        const { data } = await api.GET("/v1/reporting/reports/{reportId}/data", {
          params: { path: { reportId }, query }
        });
        return data!;
      },

      /** The CSV as text: the browser cannot follow a link that needs the bearer token. */
      async csv(reportId: string, query: ReportQuery): Promise<string> {
        const { data } = await api.GET("/v1/reporting/reports/{reportId}/csv", {
          params: { path: { reportId }, query },
          parseAs: "text"
        });
        return data ?? "";
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async requestRun(
        reportId: string,
        query: ReportQuery,
        language: "en" | "si" | "ta",
        idempotencyKey: string
      ): Promise<ReportRun> {
        const { data } = await api.POST("/v1/reporting/reports/{reportId}/runs", {
          params: { path: { reportId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { ...query, language }
        });
        return data!;
      },

      async run(runId: string): Promise<ReportRun> {
        const { data } = await api.GET("/v1/reporting/runs/{runId}", { params: { path: { runId } } });
        return data!;
      },

      async locations(): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      }
    }),
    [api, party]
  );
}
