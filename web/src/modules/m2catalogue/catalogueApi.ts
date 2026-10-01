// The calls of the catalogue module, through the shell's API client and typed by the client
// generated from openapi/m2catalogue.yaml. Never write a request or response type by hand: change
// the slice, run `make gen-clients`, and the compiler shows every place that has to follow.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m2catalogue";

export type Sku = components["schemas"]["SkuResponse"];
export type SkuStatus = Sku["status"];
export type SkuDetailsRequest = components["schemas"]["SkuDetailsRequest"];
export type Reference = components["schemas"]["CatalogueReferenceResponse"];
export type Conversion = components["schemas"]["ConversionResponse"];
export type Barcode = components["schemas"]["BarcodeResponse"];
export type Symbology = components["schemas"]["Symbology"];
export type Batch = components["schemas"]["BatchResponse"];
export type AttachImageRequest = components["schemas"]["AttachImageRequest"];
export type ImageResponse = components["schemas"]["ImageResponse"];
export type ImageUploadResponse = components["schemas"]["ImageUploadResponse"];
export type Language = "en" | "si" | "ta";

export function useCatalogueApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      /** The search covers the code and the English, Sinhala and Tamil names (22A section 7, SearchSku). */
      async listSkus(q: string, lang: Language, status?: SkuStatus): Promise<Sku[]> {
        const { data } = await api.GET("/v1/catalogue/skus", {
          params: { query: { q: q || undefined, lang, status, limit: 100 } }
        });
        return data?.items ?? [];
      },

      async getSku(skuId: string): Promise<Sku> {
        const { data } = await api.GET("/v1/catalogue/skus/{skuId}", { params: { path: { skuId } } });
        return data!;
      },

      async reference(): Promise<Reference> {
        const { data } = await api.GET("/v1/catalogue/reference");
        return data!;
      },

      async conversions(skuId: string): Promise<Conversion[]> {
        const { data } = await api.GET("/v1/catalogue/skus/{skuId}/conversions", { params: { path: { skuId } } });
        return data ?? [];
      },

      async barcodes(skuId: string): Promise<Barcode[]> {
        const { data } = await api.GET("/v1/catalogue/skus/{skuId}/barcodes", { params: { path: { skuId } } });
        return data ?? [];
      },

      async batches(skuId: string): Promise<Batch[]> {
        const { data } = await api.GET("/v1/catalogue/batches", { params: { query: { skuId } } });
        return data ?? [];
      },

      async images(skuId: string): Promise<ImageResponse[]> {
        const { data } = await api.GET("/v1/catalogue/skus/{skuId}/images", { params: { path: { skuId } } });
        return data ?? [];
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async createSku(body: SkuDetailsRequest, idempotencyKey: string): Promise<Sku> {
        const { data } = await api.POST("/v1/catalogue/skus", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async updateSku(skuId: string, body: SkuDetailsRequest, idempotencyKey: string): Promise<void> {
        await api.PATCH("/v1/catalogue/skus/{skuId}", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
      },

      /** LOCAL for the caller's own shelves; SHARED shares it with every entity (the Federation only). */
      async activate(skuId: string, target: "LOCAL" | "SHARED", idempotencyKey: string): Promise<void> {
        await api.POST("/v1/catalogue/skus/{skuId}/activate", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { target }
        });
      },

      async defineConversion(
        skuId: string,
        body: { uomCode: string; factorToBase: number; effectiveFrom: string },
        idempotencyKey: string
      ): Promise<void> {
        await api.POST("/v1/catalogue/skus/{skuId}/conversions", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
      },

      async registerBarcode(
        skuId: string,
        body: { barcode: string; symbology: Symbology; uomCode: string },
        idempotencyKey: string
      ): Promise<void> {
        await api.POST("/v1/catalogue/skus/{skuId}/barcodes", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
      },

      async attachImage(
        skuId: string,
        body: AttachImageRequest,
        idempotencyKey: string
      ): Promise<ImageUploadResponse> {
        const { data } = await api.POST("/v1/catalogue/skus/{skuId}/images", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      }
    }),
    [api]
  );
}
