// The calls of the customers module (M7, back office), through the shell's API client and typed by
// the client generated from openapi/m7customers.yaml. Never write a request or response type by
// hand: change the slice, run `make gen-clients`, and the compiler shows every place to follow.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m7customers";

export type CustomerSummary = components["schemas"]["CustomerSummary"];
export type CustomerCard = components["schemas"]["CustomerCard"];
export type Account = components["schemas"]["Account"];
export type Statement = components["schemas"]["Statement"];
export type StatementLine = Statement["lines"][number];
export type CustomerPayment = components["schemas"]["CustomerPayment"];
export type RegisterCustomerRequest = components["schemas"]["RegisterCustomerRequest"];
export type OpenAccountRequest = components["schemas"]["OpenAccountRequest"];
export type RecordPaymentRequest = components["schemas"]["RecordPaymentRequest"];
export type AmendLimitsRequest = components["schemas"]["AmendLimitsRequest"];
export type AccountHistoryEntry = components["schemas"]["AccountHistoryEntry"];
export type Adjustment = components["schemas"]["Adjustment"];
export type AdjustmentRequest = components["schemas"]["AdjustmentRequest"];
export type PrivacyRequest = components["schemas"]["PrivacyRequest"];
export type PrivacyRequestRequest = components["schemas"]["PrivacyRequestRequest"];
export type AccountAction = "suspend" | "reinstate" | "close";

export function useCustomersApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      /** The society's customers by name or phone; both empty: the first page by name. */
      async search(q: string, phone: string): Promise<CustomerSummary[]> {
        const { data } = await api.GET("/v1/customers", {
          params: { query: { q: q || undefined, phone: phone || undefined, limit: 100 } }
        });
        return data ?? [];
      },

      async customer(customerId: string): Promise<CustomerCard | null> {
        const { data } = await api.GET("/v1/customers/{customerId}", { params: { path: { customerId } } });
        return data ?? null;
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async register(request: RegisterCustomerRequest, idempotencyKey: string): Promise<CustomerCard> {
        const { data } = await api.POST("/v1/customers", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async openAccount(customerId: string, request: OpenAccountRequest, idempotencyKey: string): Promise<Account> {
        const { data } = await api.POST("/v1/customers/{customerId}/accounts", {
          params: { path: { customerId }, header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async account(accountId: string): Promise<Account | null> {
        const { data } = await api.GET("/v1/accounts/{accountId}", { params: { path: { accountId } } });
        return data ?? null;
      },

      async statement(accountId: string, from: string, to: string): Promise<Statement | null> {
        const { data } = await api.GET("/v1/accounts/{accountId}/statement", {
          params: { path: { accountId }, query: { from, to } }
        });
        return data ?? null;
      },

      /** Limit, hard block and offline cap; a higher limit answers 401 until the second factor is fresh. */
      async amendLimits(accountId: string, request: AmendLimitsRequest, idempotencyKey: string): Promise<Account> {
        const { data } = await api.POST("/v1/accounts/{accountId}/limits", {
          params: { path: { accountId }, header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async changeStatus(accountId: string, action: AccountAction, reason: string, idempotencyKey: string): Promise<Account> {
        const options = {
          params: { path: { accountId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
        };
        const { data } =
          action === "suspend"
            ? await api.POST("/v1/accounts/{accountId}/suspend", options)
            : action === "reinstate"
              ? await api.POST("/v1/accounts/{accountId}/reinstate", options)
              : await api.POST("/v1/accounts/{accountId}/close", options);
        return data!;
      },

      async history(accountId: string): Promise<AccountHistoryEntry[]> {
        const { data } = await api.GET("/v1/accounts/{accountId}/history", { params: { path: { accountId } } });
        return data ?? [];
      },

      async adjustments(accountId: string): Promise<Adjustment[]> {
        const { data } = await api.GET("/v1/accounts/{accountId}/adjustments", { params: { path: { accountId } } });
        return data ?? [];
      },

      async requestAdjustment(accountId: string, request: AdjustmentRequest, idempotencyKey: string): Promise<Adjustment> {
        const { data } = await api.POST("/v1/accounts/{accountId}/adjustments", {
          params: { path: { accountId }, header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async approveAdjustment(accountId: string, adjustmentId: string, idempotencyKey: string): Promise<Adjustment> {
        const { data } = await api.POST("/v1/accounts/{accountId}/adjustments/{adjustmentId}/approve", {
          params: { path: { accountId, adjustmentId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async reversePayment(documentId: string, reason: string, idempotencyKey: string): Promise<CustomerPayment> {
        const { data } = await api.POST("/v1/customer-payments/{documentId}/reverse", {
          params: { path: { documentId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
        });
        return data!;
      },

      async privacyRequests(): Promise<PrivacyRequest[]> {
        const { data } = await api.GET("/v1/privacy/requests", { params: { query: {} } });
        return data ?? [];
      },

      async recordPrivacyRequest(request: PrivacyRequestRequest, idempotencyKey: string): Promise<PrivacyRequest> {
        const { data } = await api.POST("/v1/privacy/requests", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async fulfilPrivacyRequest(requestId: string, outcome: string | null, idempotencyKey: string): Promise<PrivacyRequest> {
        const { data } = await api.POST("/v1/privacy/requests/{requestId}/fulfil", {
          params: { path: { requestId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { outcome }
        });
        return data!;
      },

      async refusePrivacyRequest(requestId: string, ground: string, idempotencyKey: string): Promise<PrivacyRequest> {
        const { data } = await api.POST("/v1/privacy/requests/{requestId}/refuse", {
          params: { path: { requestId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { ground }
        });
        return data!;
      },

      /** The export of a fulfilled access request, as the JSON the officer hands over. */
      async privacyExport(requestId: string): Promise<Record<string, unknown> | null> {
        const { data } = await api.GET("/v1/privacy/requests/{requestId}/export", { params: { path: { requestId } } });
        return (data as Record<string, unknown> | undefined) ?? null;
      },

      async recordPayment(
        accountId: string,
        request: RecordPaymentRequest,
        idempotencyKey: string
      ): Promise<CustomerPayment> {
        const { data } = await api.POST("/v1/accounts/{accountId}/payments", {
          params: { path: { accountId }, header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      }
    }),
    [api]
  );
}
