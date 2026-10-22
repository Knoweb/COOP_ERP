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
export type BulkImportSkusRequest = components["schemas"]["BulkImportSkusRequest"];
export type ImportReportResponse = components["schemas"]["ImportReportResponse"];
export type DuplicateSuspectResponse = components["schemas"]["DuplicateSuspectResponse"];
export type ResolveDuplicateSuspectRequest = components["schemas"]["ResolveDuplicateSuspectRequest"];
export type TaxCategory = components["schemas"]["TaxCategoryResponse"];
export type TaxRate = components["schemas"]["TaxRateResponse"];
export type DefineTaxCategoryRequest = components["schemas"]["DefineTaxCategoryRequest"];
export type PublishTaxRateRequest = components["schemas"]["PublishTaxRateRequest"];
export type ReasonRequest = components["schemas"]["ReasonRequest"];
export type CorrectBatchRequest = components["schemas"]["CorrectBatchRequest"];
export type LinkBarcodeToBatchRequest = components["schemas"]["LinkBarcodeToBatchRequest"];
export type SupplierResponse = components["schemas"]["SupplierResponse"];
export type RegisterSupplierRequest = components["schemas"]["RegisterSupplierRequest"];
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

      async getBatch(batchId: string): Promise<Batch> {
        const { data } = await api.GET("/v1/catalogue/batches/{batchId}", { params: { path: { batchId } } });
        return data!;
      },

      async correctBatch(batchId: string, body: CorrectBatchRequest, idempotencyKey: string): Promise<Batch> {
        const { data } = await api.POST("/v1/catalogue/batches/{batchId}/correct", {
          params: { path: { batchId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
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

      async deactivateSku(skuId: string, body: ReasonRequest, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/catalogue/skus/{skuId}/deactivate", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
      },

      async reactivateSku(skuId: string, body: ReasonRequest, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/catalogue/skus/{skuId}/reactivate", {
          params: { path: { skuId }, header: { "Idempotency-Key": idempotencyKey } },
          body
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

      async retireBarcode(
        skuId: string,
        barcode: string,
        symbology: Symbology,
        reason: { reasonCode?: string; reasonText?: string },
        idempotencyKey: string
      ): Promise<void> {
        await api.DELETE("/v1/catalogue/skus/{skuId}/barcodes/{barcode}", {
          params: {
            path: { skuId, barcode },
            query: { symbology, reasonCode: reason.reasonCode, reasonText: reason.reasonText },
            header: { "Idempotency-Key": idempotencyKey }
          }
        });
      },

      async linkBarcodeToBatch(
        skuId: string,
        barcode: string,
        body: LinkBarcodeToBatchRequest,
        idempotencyKey: string
      ): Promise<void> {
        await api.POST("/v1/catalogue/skus/{skuId}/barcodes/{barcode}/link", {
          params: { path: { skuId, barcode }, header: { "Idempotency-Key": idempotencyKey } },
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
      },

      async retireImage(
        skuId: string,
        imageId: string,
        idempotencyKey: string
      ): Promise<void> {
        await api.DELETE("/v1/catalogue/skus/{skuId}/images/{imageId}", {
          params: { path: { skuId, imageId }, header: { "Idempotency-Key": idempotencyKey } }
        });
      },

      async importSkus(body: BulkImportSkusRequest, idempotencyKey: string): Promise<ImportReportResponse> {
        const { data } = await api.POST("/v1/catalogue/import", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async listDuplicateSuspects(status?: "OPEN" | "MERGED" | "DISMISSED", limit?: number): Promise<DuplicateSuspectResponse[]> {
        const { data } = await api.GET("/v1/catalogue/steward/suspects", {
          params: { query: { status, limit } }
        });
        return data ?? [];
      },

      async resolveDuplicateSuspect(
        suspectId: string,
        body: ResolveDuplicateSuspectRequest,
        idempotencyKey: string
      ): Promise<void> {
        await api.POST("/v1/catalogue/steward/suspects/{suspectId}/resolve", {
          params: { path: { suspectId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
      },

      async listSuppliers(): Promise<SupplierResponse[]> {
        const { data } = await api.GET("/v1/catalogue/suppliers");
        return data ?? [];
      },

      async registerSupplier(body: RegisterSupplierRequest, idempotencyKey: string): Promise<SupplierResponse> {
        const { data } = await api.POST("/v1/catalogue/suppliers", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async listTaxCategories(): Promise<TaxCategory[]> {
        const { data } = await api.GET("/v1/catalogue/tax/categories");
        return data ?? [];
      },

      async defineTaxCategory(
        body: DefineTaxCategoryRequest,
        idempotencyKey: string
      ): Promise<TaxCategory> {
        const { data } = await api.POST("/v1/catalogue/tax/categories", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async listTaxRates(categoryId?: string, date?: string): Promise<TaxRate[]> {
        const { data } = await api.GET("/v1/catalogue/tax/rates", {
          params: { query: { categoryId, date } }
        });
        return data ?? [];
      },

      async publishTaxRate(
        body: PublishTaxRateRequest,
        idempotencyKey: string
      ): Promise<TaxRate> {
        const { data } = await api.POST("/v1/catalogue/tax/rates", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      }
    }),
    [api]
  );
}
