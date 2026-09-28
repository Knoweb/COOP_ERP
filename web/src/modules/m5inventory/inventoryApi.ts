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
export type StockCardLine = components["schemas"]["StockCardLineResponse"];
export type Transfer =components["schemas"]["TransferResponse"];
export type IssueTransferRequest = components["schemas"]["IssueTransferRequest"];
export type Count = components["schemas"]["CountResponse"];
export type ScheduleCountRequest = components["schemas"]["ScheduleCountRequest"];
export type CountLineRequest = components["schemas"]["CountLineRequest"];
export type NegativeLot = components["schemas"]["NegativeLotResponse"];
export type WriteOff = components["schemas"]["WriteOffResponse"];
export type RequestWriteOffRequest = components["schemas"]["RequestWriteOffRequest"];
export type Recipe = components["schemas"]["RecipeResponse"];
export type DefineRecipeRequest = components["schemas"]["DefineRecipeRequest"];
export type Repack = components["schemas"]["RepackResponse"];
export type ExecuteRepackRequest = components["schemas"]["ExecuteRepackRequest"];
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

      async stockCard(locationId: string, skuId: string): Promise<StockCardLine[]> {
        const { data } = await api.GET("/v1/inventory/locations/{locationId}/movements", {
          params: { path: { locationId }, query: { skuId } }
        });
        return data ?? [];
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

      // ---- stock control (M5-11, M5-13, repack) ---------------------------------------------

      async counts(locationId: string): Promise<Count[]> {
        const { data } = await api.GET("/v1/inventory/counts", { params: { query: { locationId } } });
        return data ?? [];
      },

      async count(taskId: string): Promise<Count> {
        const { data } = await api.GET("/v1/inventory/counts/{taskId}", { params: { path: { taskId } } });
        return data!;
      },

      async scheduleCount(request: ScheduleCountRequest, idempotencyKey: string): Promise<Count> {
        const { data } = await api.POST("/v1/inventory/counts", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async startCount(taskId: string, idempotencyKey: string): Promise<Count> {
        const { data } = await api.POST("/v1/inventory/counts/{taskId}/start", {
          params: { path: { taskId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async submitCount(taskId: string, lines: CountLineRequest[], idempotencyKey: string): Promise<Count> {
        const { data } = await api.POST("/v1/inventory/counts/{taskId}/submit", {
          params: { path: { taskId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { lines }
        });
        return data!;
      },

      async approveAdjustment(taskId: string, idempotencyKey: string): Promise<Count> {
        const { data } = await api.POST("/v1/inventory/counts/{taskId}/approve", {
          params: { path: { taskId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async rejectAdjustment(taskId: string, reason: string, idempotencyKey: string): Promise<Count> {
        const { data } = await api.POST("/v1/inventory/counts/{taskId}/reject", {
          params: { path: { taskId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
        });
        return data!;
      },

      async negativeLots(locationId: string): Promise<NegativeLot[]> {
        const { data } = await api.GET("/v1/inventory/locations/{locationId}/negative-lots", {
          params: { path: { locationId } }
        });
        return data ?? [];
      },

      async acknowledgeNegativeLot(stockLotId: string, reason: string, idempotencyKey: string): Promise<NegativeLot> {
        const { data } = await api.POST("/v1/inventory/lots/{stockLotId}/acknowledge-negative", {
          params: { path: { stockLotId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
        });
        return data!;
      },

      async writeOffs(locationId: string): Promise<WriteOff[]> {
        const { data } = await api.GET("/v1/inventory/write-offs", { params: { query: { locationId } } });
        return data ?? [];
      },

      async writeOff(writeOffId: string): Promise<WriteOff> {
        const { data } = await api.GET("/v1/inventory/write-offs/{writeOffId}", {
          params: { path: { writeOffId } }
        });
        return data!;
      },

      async requestWriteOff(request: RequestWriteOffRequest, idempotencyKey: string): Promise<WriteOff> {
        const { data } = await api.POST("/v1/inventory/write-offs", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      /** Authorises one photograph, then PUTs its bytes to the store at the URL the server signed. */
      async addWriteOffPhoto(writeOffId: string, file: File, idempotencyKey: string): Promise<void> {
        const { data } = await api.POST("/v1/inventory/write-offs/{writeOffId}/photos", {
          params: { path: { writeOffId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { contentType: file.type, contentLength: file.size }
        });
        const upload = await fetch(data!.url, { method: "PUT", headers: { "Content-Type": file.type }, body: file });
        if (!upload.ok) {
          throw new Error(`upload ${upload.status}`);
        }
      },

      async writeOffStep(
        writeOffId: string,
        step: "submit" | "witness" | "approve",
        idempotencyKey: string
      ): Promise<WriteOff> {
        const params = { path: { writeOffId }, header: { "Idempotency-Key": idempotencyKey } };
        const { data } =
          step === "submit"
            ? await api.POST("/v1/inventory/write-offs/{writeOffId}/submit", { params })
            : step === "witness"
              ? await api.POST("/v1/inventory/write-offs/{writeOffId}/witness", { params })
              : await api.POST("/v1/inventory/write-offs/{writeOffId}/approve", { params });
        return data!;
      },

      async rejectWriteOff(writeOffId: string, reason: string, idempotencyKey: string): Promise<WriteOff> {
        const { data } = await api.POST("/v1/inventory/write-offs/{writeOffId}/reject", {
          params: { path: { writeOffId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
        });
        return data!;
      },

      async recipes(): Promise<Recipe[]> {
        const { data } = await api.GET("/v1/inventory/recipes");
        return data ?? [];
      },

      async defineRecipe(request: DefineRecipeRequest, idempotencyKey: string): Promise<Recipe> {
        const { data } = await api.POST("/v1/inventory/recipes", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async retireRecipe(recipeId: string, idempotencyKey: string): Promise<Recipe> {
        const { data } = await api.POST("/v1/inventory/recipes/{recipeId}/retire", {
          params: { path: { recipeId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async repacks(locationId: string): Promise<Repack[]> {
        const { data } = await api.GET("/v1/inventory/repacks", { params: { query: { locationId } } });
        return data ?? [];
      },

      async executeRepack(request: ExecuteRepackRequest, idempotencyKey: string): Promise<Repack> {
        const { data } = await api.POST("/v1/inventory/repacks", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async reverseRepack(repackId: string, reason: string, idempotencyKey: string): Promise<Repack> {
        const { data } = await api.POST("/v1/inventory/repacks/{repackId}/reverse", {
          params: { path: { repackId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
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
