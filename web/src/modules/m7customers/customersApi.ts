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
