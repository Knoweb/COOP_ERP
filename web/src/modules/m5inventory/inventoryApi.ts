// The calls of the inventory module, through the shell's API client and typed by the clients
// generated from openapi/m5inventory.yaml; the locations come from M1's published list and the
// item names from M2's, never from M5's own tables.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m5inventory";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";
import type { components as catalogueComponents, paths as cataloguePaths } from "../../generated/m2catalogue";

export type LotBalance = components["schemas"]["LotBalanceResponse"];
export type Availability = components["schemas"]["AvailabilityResponse"];
export type OpeningBalance = components["schemas"]["OpeningBalanceResponse"];
export type OpeningBalanceLineRequest = components["schemas"]["OpeningBalanceLineRequest"];
export type Transfer = components["schemas"]["TransferResponse"];
export type IssueTransferRequest = components["schemas"]["IssueTransferRequest"];
export type Location = partyComponents["schemas"]["LocationResponse"];
export type Sku = catalogueComponents["schemas"]["SkuResponse"];

export function useInventoryApi() {
  const api = useApiClient<paths>();
  const party = useApiClient<partyPaths>();
  const catalogue = useApiClient<cataloguePaths>();

  return useMemo(
    () => ({
      async locations(): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      },

      async balances(locationId: string): Promise<LotBalance[]> {
        const { data } = await api.GET("/v1/inventory/locations/{locationId}/balances", {
          params: { path: { locationId } }
        });
        return data ?? [];
      },

      async availability(locationId: string, skuIds: string[]): Promise<Availability[]> {
        if (skuIds.length === 0) {
          return [];
        }
        const { data } = await api.GET("/v1/inventory/availability", {
          params: { query: { locationIds: [locationId], skuIds } }
        });
        return data ?? [];
      },

      async searchSkus(q: string): Promise<Sku[]> {
        const { data } = await catalogue.GET("/v1/catalogue/skus", { params: { query: { q, limit: 20 } } });
        return (data?.items ?? []).filter((sku) => sku.status === "LOCAL" || sku.status === "SHARED");
      },

      async getSku(skuId: string): Promise<Sku | null> {
        const { data } = await catalogue.GET("/v1/catalogue/skus/{skuId}", { params: { path: { skuId } } });
        return data ?? null;
      },

      async openingBalance(id: string): Promise<OpeningBalance> {
        const { data } = await api.GET("/v1/inventory/opening-balances/{openingBalanceId}", {
          params: { path: { openingBalanceId: id } }
        });
        return data!;
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async prepare(locationId: string, lines: OpeningBalanceLineRequest[], idempotencyKey: string): Promise<OpeningBalance> {
        const { data } = await api.POST("/v1/inventory/opening-balances", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { locationId, lines }
        });
        return data!;
      },

      async sign(id: string, idempotencyKey: string): Promise<OpeningBalance> {
        const { data } = await api.POST("/v1/inventory/opening-balances/{openingBalanceId}/sign", {
          params: { path: { openingBalanceId: id }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async transfers(locationId: string): Promise<Transfer[]> {
        const { data } = await api.GET("/v1/inventory/transfers", { params: { query: { locationId } } });
        return data ?? [];
      },

      async issueTransfer(request: IssueTransferRequest, idempotencyKey: string): Promise<Transfer> {
        const { data } = await api.POST("/v1/inventory/transfers", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async receiveTransfer(transferId: string, idempotencyKey: string): Promise<Transfer> {
        const { data } = await api.POST("/v1/inventory/transfers/{transferId}/receive", {
          params: { path: { transferId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async countersign(id: string, idempotencyKey: string): Promise<OpeningBalance> {
        const { data } = await api.POST("/v1/inventory/opening-balances/{openingBalanceId}/countersign", {
          params: { path: { openingBalanceId: id }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      }
    }),
    [api, party, catalogue]
  );
}
