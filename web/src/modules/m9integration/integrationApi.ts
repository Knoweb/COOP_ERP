// The calls of the integration module (M9), through the shell's API client and typed by the
// client generated from openapi/m9integration.yaml. Never write a request or response type by
// hand: change the slice, run `make gen-clients`, and the compiler shows every place to follow.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m9integration";

export type JournalExport = components["schemas"]["JournalExportResponse"];
export type JournalLine = components["schemas"]["JournalLineResponse"];
export type Reconciliation = components["schemas"]["ReconciliationResponse"];
export type PendingPostings = components["schemas"]["PendingPostingsResponse"];
export type SupplementDue = components["schemas"]["SupplementDueResponse"];
export type NotificationTemplate = components["schemas"]["NotificationTemplateResponse"];
export type NotificationRule = components["schemas"]["NotificationRuleResponse"];
export type NotificationLogEntry = components["schemas"]["NotificationLogResponse"];
export type LogStatus = NonNullable<NotificationLogEntry["status"]>;

export function useIntegrationApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      async exports(): Promise<JournalExport[]> {
        const { data } = await api.GET("/v1/integration/journal-exports");
        return data ?? [];
      },

      /**
       * `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry.
       * `provisional` says the caller knows the period is still open and wants the file anyway.
       */
      async requestExport(
        periodFrom: string,
        periodTo: string,
        provisional: boolean,
        idempotencyKey: string
      ): Promise<JournalExport> {
        const { data } = await api.POST("/v1/integration/journal-exports", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { periodFrom, periodTo, provisional }
        });
        return data!;
      },

      async pending(from: string, to: string): Promise<PendingPostings> {
        const { data } = await api.GET("/v1/integration/journal-postings/pending", {
          params: { query: { from, to } }
        });
        return data!;
      },

      /** Postings for periods already exported that no export took: the supplement due. */
      async supplementDue(): Promise<SupplementDue> {
        const { data } = await api.GET("/v1/integration/journal-postings/supplement-due");
        return data!;
      },

      async lines(exportId: string): Promise<JournalLine[]> {
        const { data } = await api.GET("/v1/integration/journal-exports/{exportId}/lines", {
          params: { path: { exportId } }
        });
        return data ?? [];
      },

      async reconciliation(exportId: string): Promise<Reconciliation> {
        const { data } = await api.GET("/v1/integration/journal-exports/{exportId}/reconciliation", {
          params: { path: { exportId } }
        });
        return data!;
      },

      /** The CSV as text: the browser cannot follow a link that needs the bearer token. */
      async file(exportId: string): Promise<string> {
        const { data } = await api.GET("/v1/integration/journal-exports/{exportId}/file", {
          params: { path: { exportId } },
          parseAs: "text"
        });
        return data ?? "";
      },

      async templates(): Promise<NotificationTemplate[]> {
        const { data } = await api.GET("/v1/integration/templates");
        return data ?? [];
      },

      async rules(): Promise<NotificationRule[]> {
        const { data } = await api.GET("/v1/integration/rules");
        return data ?? [];
      },

      async setRuleStatus(ruleId: string, active: boolean, idempotencyKey: string): Promise<NotificationRule> {
        const params = { path: { ruleId }, header: { "Idempotency-Key": idempotencyKey } };
        const { data } = active
          ? await api.POST("/v1/integration/rules/{ruleId}/activate", { params })
          : await api.POST("/v1/integration/rules/{ruleId}/retire", { params });
        return data!;
      },

      async log(status: LogStatus | undefined): Promise<NotificationLogEntry[]> {
        const { data } = await api.GET("/v1/integration/notifications/log", {
          params: { query: status ? { status } : {} }
        });
        return data ?? [];
      }
    }),
    [api]
  );
}
