// The calls of the point of sale module (M6), through the shell's API client and typed by the
// clients generated from openapi/m6pos.yaml. The receipts and sessions are the tills' facts as
// central keeps them (read-only here: a sale is made at the till, never on this screen); the
// locations and till positions come from M1's published lists and the item names from M2's.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m6pos";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";
import type { components as catalogueComponents, paths as cataloguePaths } from "../../generated/m2catalogue";

export type Receipt = components["schemas"]["ReceiptResponse"];
export type TillSession = components["schemas"]["SessionResponse"];
export type Location = partyComponents["schemas"]["LocationResponse"];
export type TillPosition = partyComponents["schemas"]["TillPositionResponse"];
export type Sku = catalogueComponents["schemas"]["SkuResponse"];

export function usePosApi() {
  const api = useApiClient<paths>();
  const party = useApiClient<partyPaths>();
  const catalogue = useApiClient<cataloguePaths>();

  return useMemo(
    () => ({
      async locations(): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      },

      async positions(locationId: string): Promise<TillPosition[]> {
        const { data } = await party.GET("/v1/party/locations/{locationId}/positions", {
          params: { path: { locationId } }
        });
        return data ?? [];
      },

      async receipts(locationId: string): Promise<Receipt[]> {
        const { data } = await api.GET("/v1/pos/receipts", { params: { query: { locationId } } });
        return data ?? [];
      },

      async sessions(locationId: string): Promise<TillSession[]> {
        const { data } = await api.GET("/v1/pos/sessions", { params: { query: { locationId } } });
        return data ?? [];
      },

      async getSku(skuId: string): Promise<Sku | null> {
        const { data } = await catalogue.GET("/v1/catalogue/skus/{skuId}", { params: { path: { skuId } } });
        return data ?? null;
      }
    }),
    [api, party, catalogue]
  );
}
