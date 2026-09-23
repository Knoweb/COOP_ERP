// The calls of the pricing module, through the shell's API client and typed by the client
// generated from openapi/m3pricing.yaml. Never write a request or response type by hand: change
// the slice, run `make gen-clients`, and the compiler shows every place that has to follow.
//
// The token, the language, the scope and error handling are the shell's business
// (shell/api/client.ts). What is left for a module is what only it knows: which operations
// it has.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m3pricing";

export type PriceList = components["schemas"]["PriceListResponse"];
export type RegisterPriceListRequest = components["schemas"]["RegisterPriceListRequest"];

export function usePricingApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      async listPriceLists(): Promise<PriceList[]> {
        const { data } = await api.GET("/v1/pricing/price-lists");
        return data ?? [];
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async registerPriceList(request: RegisterPriceListRequest, idempotencyKey: string): Promise<PriceList> {
        const { data } = await api.POST("/v1/pricing/price-lists", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      }
    }),
    [api]
  );
}
