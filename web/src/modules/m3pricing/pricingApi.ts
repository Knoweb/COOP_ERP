// The calls of the pricing module, through the shell's API client and typed by the client
// generated from openapi/m3pricing.yaml. Never write a request or response type by hand: change
// the slice, run `make gen-clients`, and the compiler shows every place that has to follow.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m3pricing";
import type { components as catalogueComponents, paths as cataloguePaths } from "../../generated/m2catalogue";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";

export type PriceList = components["schemas"]["PriceListResponse"];
export type PriceListDetail = components["schemas"]["PriceListDetailResponse"];
export type PriceListLine = components["schemas"]["PriceListLineResponse"];
export type PriceListLineInput = components["schemas"]["PriceListLineInput"];
export type SetLinesResponse = components["schemas"]["SetLinesResponse"];
export type LineOutcome = components["schemas"]["LineOutcome"];
export type ControlPrice = components["schemas"]["ControlPriceResponse"];
export type EnterControlPriceRequest = components["schemas"]["EnterControlPriceRequest"];
export type RescindControlPriceRequest = components["schemas"]["RescindControlPriceRequest"];
export type MrpPolicy = components["schemas"]["MrpPolicyResponse"];
export type SetMrpPolicyRequest = components["schemas"]["SetMrpPolicyRequest"];
export type RetailPrice = components["schemas"]["RetailPriceResponse"];
export type Sku = catalogueComponents["schemas"]["SkuResponse"];
export type Location = partyComponents["schemas"]["LocationResponse"];
export type Relationship = partyComponents["schemas"]["RelationshipResponse"];
export type Entity = partyComponents["schemas"]["EntityResponse"];

export function usePricingApi() {
  const api = useApiClient<paths>();
  // The SKU picker reads M2's published search (GET /v1/catalogue/skus); M3 owns no SKU data.
  const catalogue = useApiClient<cataloguePaths>();
  // The shop picker of the shelf price check reads M1's published location list.
  const party = useApiClient<partyPaths>();

  return useMemo(
    () => ({
      async searchSkus(q: string): Promise<Sku[]> {
        const { data } = await catalogue.GET("/v1/catalogue/skus", { params: { query: { q, limit: 20 } } });
        return data?.items ?? [];
      },

      async getSku(skuId: string): Promise<Sku | null> {
        const { data } = await catalogue.GET("/v1/catalogue/skus/{skuId}", { params: { path: { skuId } } });
        return data ?? null;
      },

      async locations(): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      },

      /** The relationships in which the caller sells, from M1's published list; M3 owns none. */
      async sellerRelationships(): Promise<Relationship[]> {
        const { data } = await party.GET("/v1/party/relationships", { params: { query: { side: "SELLER" } } });
        return data ?? [];
      },

      async entity(entityId: string): Promise<Entity | null> {
        const { data } = await party.GET("/v1/party/entities/{entityId}", { params: { path: { entityId } } });
        return data ?? null;
      },

      async listPriceLists(kind?: PriceList["kind"]): Promise<PriceList[]> {
        const { data } = await api.GET("/v1/pricing/lists", { params: { query: { kind } } });
        return data ?? [];
      },

      async getPriceList(listId: string): Promise<PriceListDetail> {
        const { data } = await api.GET("/v1/pricing/lists/{listId}", { params: { path: { listId } } });
        return data!;
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async createPriceList(name: string, idempotencyKey: string, kind: PriceList["kind"] = "TRADE"): Promise<PriceList> {
        const { data } = await api.POST("/v1/pricing/lists", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { kind, name }
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
      },

      /** The Federation's published advisory prices in force on the date (every scope reads them). */
      async advisoryLines(date: string): Promise<PriceListLine[]> {
        const { data } = await api.GET("/v1/pricing/advisory-lines", { params: { query: { date } } });
        return data ?? [];
      },

      /** Every control price, newest first by item: the history. */
      async listControlPrices(): Promise<ControlPrice[]> {
        const { data } = await api.GET("/v1/pricing/control-prices", { params: { query: {} } });
        return data ?? [];
      },

      async enterControlPrice(body: EnterControlPriceRequest, idempotencyKey: string): Promise<ControlPrice> {
        const { data } = await api.POST("/v1/pricing/control-prices", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async rescindControlPrice(
        controlPriceId: string,
        body: RescindControlPriceRequest,
        idempotencyKey: string
      ): Promise<ControlPrice> {
        const { data } = await api.POST("/v1/pricing/control-prices/{controlPriceId}/rescind", {
          params: { path: { controlPriceId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async listMrpPolicies(): Promise<MrpPolicy[]> {
        const { data } = await api.GET("/v1/pricing/mrp-policies");
        return data ?? [];
      },

      async setMrpPolicy(body: SetMrpPolicyRequest, idempotencyKey: string): Promise<MrpPolicy> {
        const { data } = await api.PUT("/v1/pricing/mrp-policies", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      /** The shelf price of one unit of the item at the shop on the date; null when the shop is not visible. */
      async resolveRetailPrice(locationId: string, skuId: string, uom: string, date: string): Promise<RetailPrice | null> {
        const { data } = await api.GET("/v1/pricing/resolve/retail", {
          params: { query: { locationId, skuId, uom, qty: 1, date } }
        });
        return data ?? null;
      }
    }),
    [api, catalogue, party]
  );
}
