// The calls of the pricing module, through the shell's API client and typed by the client
// generated from openapi/m3pricing.yaml. Never write a request or response type by hand: change
// the slice, run `make gen-clients`, and the compiler shows every place that has to follow.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m3pricing";

export type PriceList = components["schemas"]["PriceListResponse"];
export type PriceListDetail = components["schemas"]["PriceListDetailResponse"];
export type PriceListLineInput = components["schemas"]["PriceListLineInput"];
export type SetLinesResponse = components["schemas"]["SetLinesResponse"];

export function usePricingApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      async listPriceLists(kind?: PriceList["kind"]): Promise<PriceList[]> {
        const { data } = await api.GET("/v1/pricing/lists", { params: { query: { kind } } });
        return data ?? [];
      },

      async getPriceList(listId: string): Promise<PriceListDetail> {
        const { data } = await api.GET("/v1/pricing/lists/{listId}", { params: { path: { listId } } });
        return data!;
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async createPriceList(name: string, idempotencyKey: string): Promise<PriceList> {
        const { data } = await api.POST("/v1/pricing/lists", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { kind: "TRADE", name }
        });
        return data!;
      },

      async draftNewVersion(listId: string, idempotencyKey: string): Promise<PriceList> {
        const { data } = await api.POST("/v1/pricing/lists/{listId}/versions", {
          params: { path: { listId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async setLines(listId: string, lines: PriceListLineInput[], idempotencyKey: string): Promise<SetLinesResponse> {
        const { data } = await api.PUT("/v1/pricing/lists/{listId}/lines", {
          params: { path: { listId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { lines }
        });
        return data!;
      },

      async publish(listId: string, applyFrom: string, idempotencyKey: string): Promise<PriceList> {
        const { data } = await api.POST("/v1/pricing/lists/{listId}/publish", {
          params: { path: { listId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { applyFrom }
        });
        return data!;
      }
    }),
    [api]
  );
}
