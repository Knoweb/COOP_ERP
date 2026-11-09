// The calls of the inventory module, through the shell's API client and typed by the clients
// generated from openapi/m5inventory.yaml; the locations come from M1's published list and the
// item names from M2's, never from M5's own tables.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m5inventory";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";
import type { components as catalogueComponents, paths as cataloguePaths } from "../../generated/m2catalogue";
import type { components as tradingComponents, paths as tradingPaths } from "../../generated/m4trading";

export type LotBalance = components["schemas"]["LotBalanceResponse"];
export type Availability = components["schemas"]["AvailabilityResponse"];
export type OpeningBalance = components["schemas"]["OpeningBalanceResponse"];
export type OpeningBalanceLineRequest = components["schemas"]["OpeningBalanceLineRequest"];
export type BulkStockReport = components["schemas"]["BulkStockReport"];
export type StockProblem = components["schemas"]["StockProblem"];
export type StockRowPreview = components["schemas"]["StockRowPreview"];
export type StockRowRequest = components["schemas"]["StockRowRequest"];
export type StockCardLine = components["schemas"]["StockCardLineResponse"];
export type PickList = components["schemas"]["PickListResponse"];
export type PickReplacementCandidate = components["schemas"]["PickReplacementCandidateResponse"];
export type OverridePickRequest = components["schemas"]["OverridePickRequest"];
export type Transfer = components["schemas"]["TransferResponse"];
export type IssueTransferRequest = components["schemas"]["IssueTransferRequest"];
export type ReceiveTransferRequest = components["schemas"]["ReceiveTransferRequest"];
export type CancelTransferRequest = components["schemas"]["CancelTransferRequest"];
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
export type Batch = catalogueComponents["schemas"]["BatchResponse"];
export type TransferRequest = tradingComponents["schemas"]["TransferRequestResponse"];
export type RequestTransferRequest = tradingComponents["schemas"]["RequestTransferRequest"];
export type ReorderControl = components["schemas"]["ReorderControlResponse"];
export type ConfigureReorderControlRequest = components["schemas"]["ConfigureReorderControlRequest"];
export type RepriceStockLotRequest = components["schemas"]["RepriceStockLotRequest"];
export type StockRepricingResponse = components["schemas"]["StockRepricingResponse"];
export type EvaluateLowStockResponse = components["schemas"]["EvaluateLowStockResponse"];
export type ExpiryAlertItem = components["schemas"]["ExpiryAlertItemResponse"];
export type SlowMovingStock = components["schemas"]["SlowMovingStockResponse"];
export type ExpiryAlertScan = components["schemas"]["ExpiryAlertScanResponse"];
export type StockSummary = components["schemas"]["StockSummaryResponse"];
export type StockExplanation = components["schemas"]["StockExplanationResponse"];
export type StockReservationDetail = components["schemas"]["StockReservationDetail"];
export type StockInTransitDetail = components["schemas"]["StockInTransitDetail"];
export type StockCountDetail = components["schemas"]["StockCountDetail"];
export type DepartmentCategory = catalogueComponents["schemas"]["DepartmentCategoryResponse"];
export type Uom = catalogueComponents["schemas"]["UomResponse"];
export type OpenStockTakeSessionRequest = components["schemas"]["OpenStockTakeSessionRequest"];
export type StockTakeSessionResponse = components["schemas"]["StockTakeSessionResponse"];
export type StockTakeScanRequest = components["schemas"]["StockTakeScanRequest"];
export type StockTakeScanResponse = components["schemas"]["StockTakeScanResponse"];
export type StockTakeScanItemResponse = components["schemas"]["StockTakeScanItemResponse"];
export type StockTakeBatchScanRequest = components["schemas"]["StockTakeBatchScanRequest"];
export type StockTakeBatchScanResponse = components["schemas"]["StockTakeBatchScanResponse"];
export type StockTakeReviewResponse = components["schemas"]["StockTakeReviewResponse"];
export type StockTakeReviewLine = components["schemas"]["StockTakeReviewLine"];
export type StockTakeReconcileRequest = components["schemas"]["StockTakeReconcileRequest"];
export type StockTakeReconcileResponse = components["schemas"]["StockTakeReconcileResponse"];

export function useInventoryApi() {
  const api = useApiClient<paths>();
  const party = useApiClient<partyPaths>();
  const catalogue = useApiClient<cataloguePaths>();
  const trading = useApiClient<tradingPaths>();

  return useMemo(
    () => ({
      async locations(): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      },

      async stockSummaries(
        locationId: string,
        params?: {
          departmentId?: string;
          uomCode?: string;
          skuId?: string;
          includeZero?: boolean;
          discrepancyOnly?: boolean;
        }
      ): Promise<StockSummary[]> {
        const { data } = await api.GET("/v1/inventory/locations/{locationId}/stock-summary", {
          params: {
            path: { locationId },
            query: {
              departmentId: params?.departmentId,
              uomCode: params?.uomCode,
              skuId: params?.skuId,
              includeZero: params?.includeZero,
              discrepancyOnly: params?.discrepancyOnly
            }
          }
        });
        return data ?? [];
      },

      async stockExplanation(locationId: string, skuId: string): Promise<StockExplanation | null> {
        const { data } = await api.GET("/v1/inventory/locations/{locationId}/skus/{skuId}/explanation", {
          params: { path: { locationId, skuId } }
        });
        return data ?? null;
      },

      async departments(): Promise<DepartmentCategory[]> {
        const { data } = await catalogue.GET("/v1/catalogue/departments");
        return data ?? [];
      },

      async uoms(): Promise<Uom[]> {
        const { data } = await catalogue.GET("/v1/catalogue/uoms");
        return data ?? [];
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

      async batch(batchId: string): Promise<Batch | null> {
        const { data } = await catalogue.GET("/v1/catalogue/batches/{batchId}", {
          params: { path: { batchId } }
        });
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

      async downloadOpeningStockTemplate(): Promise<Blob> {
        const { data } = await api.GET("/v1/inventory/opening-balances/template", {
          parseAs: "blob"
        });
        if (data instanceof Blob) {
          return data;
        }
        return new Blob([data as unknown as BlobPart], {
          type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        });
      },

      async bulkImportOpeningStock(
        locationId: string,
        mode: "VALIDATE" | "COMMIT",
        sheet: string | undefined,
        lines: StockRowRequest[] | undefined,
        idempotencyKey: string
      ): Promise<BulkStockReport> {
        const { data } = await api.POST("/v1/inventory/opening-balances/bulk", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { locationId, mode, sheet, lines }
        });
        return data!;
      },

      async rejectOpeningBalance(
        openingBalanceId: string,
        reason: string,
        idempotencyKey: string
      ): Promise<OpeningBalance> {
        const { data } = await api.POST("/v1/inventory/opening-balances/{openingBalanceId}/reject", {
          params: { path: { openingBalanceId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
        });
        return data!;
      },

      async stockCard(locationId: string, skuId: string): Promise<StockCardLine[]> {
        const { data } = await api.GET("/v1/inventory/locations/{locationId}/movements", {
          params: { path: { locationId }, query: { skuId } }
        });
        return data ?? [];
      },

      async pickList(deliveryNoteId: string): Promise<PickList> {
        const { data } = await api.GET("/v1/inventory/pick-lists/{deliveryNoteId}", {
          params: { path: { deliveryNoteId } }
        });
        return data!;
      },

      async pickReplacementCandidates(
        deliveryNoteId: string,
        pickLineId: string
      ): Promise<PickReplacementCandidate[]> {
        const { data } = await api.GET(
          "/v1/inventory/pick-lists/{deliveryNoteId}/lines/{pickLineId}/replacement-candidates",
          { params: { path: { deliveryNoteId, pickLineId } } }
        );
        return data ?? [];
      },

      async overridePick(
        deliveryNoteId: string,
        pickLineId: string,
        request: OverridePickRequest,
        idempotencyKey: string
      ): Promise<void> {
        await api.POST("/v1/inventory/pick-lists/{deliveryNoteId}/lines/{pickLineId}/override", {
          params: {
            path: { deliveryNoteId, pickLineId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: request
        });
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

      async dispatchTransfer(transferId: string, idempotencyKey: string): Promise<Transfer> {
        const { data } = await api.POST("/v1/inventory/transfers/{transferId}/dispatch", {
          params: { path: { transferId }, header: { "Idempotency-Key": idempotencyKey } }
        });
        return data!;
      },

      async receiveTransfer(
        transferId: string,
        idempotencyKey: string,
        request?: ReceiveTransferRequest
      ): Promise<Transfer> {
        const { data } = await api.POST("/v1/inventory/transfers/{transferId}/receive", {
          params: { path: { transferId }, header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async cancelTransfer(
        transferId: string,
        request: CancelTransferRequest,
        idempotencyKey: string
      ): Promise<Transfer> {
        const { data } = await api.POST("/v1/inventory/transfers/{transferId}/cancel", {
          params: { path: { transferId }, header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      // ---- transfer requests (M4-10: M4's slice, the stores' side of a transfer) ---------------

      /** The requests the caller's scope reads: a shop its own, the society all; newest first. */
      async transferRequests(): Promise<TransferRequest[]> {
        const { data } = await trading.GET("/v1/trading/transfer-requests");
        return data ?? [];
      },

      /** The shop asks for stock; the source is left to the society when the shop does not name it. */
      async requestTransfer(body: RequestTransferRequest, idempotencyKey: string): Promise<TransferRequest> {
        const { data } = await trading.POST("/v1/trading/transfer-requests", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      /** The society approves from the location it names (or the one the shop named); M5 issues the transfer. */
      async approveTransferRequest(
        requestId: string,
        fromLocationId: string | undefined,
        idempotencyKey: string
      ): Promise<TransferRequest> {
        const { data } = await trading.POST("/v1/trading/transfer-requests/{requestId}/approve", {
          params: { path: { requestId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { fromLocationId }
        });
        return data!;
      },

      async rejectTransferRequest(requestId: string, reason: string, idempotencyKey: string): Promise<TransferRequest> {
        const { data } = await trading.POST("/v1/trading/transfer-requests/{requestId}/reject", {
          params: { path: { requestId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { reason }
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
      },

      async listReorderControls(locationId: string, skuId?: string): Promise<ReorderControl[]> {
        const { data } = await api.GET("/v1/inventory/reorder-controls", {
          params: { query: { locationId, skuId } }
        });
        return data ?? [];
      },

      async configureReorderControl(request: ConfigureReorderControlRequest, idempotencyKey: string): Promise<ReorderControl> {
        const { data } = await api.PUT("/v1/inventory/reorder-controls", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async deleteReorderControl(locationId: string, skuId: string, idempotencyKey: string): Promise<void> {
        await api.DELETE("/v1/inventory/reorder-controls", {
          params: {
            header: { "Idempotency-Key": idempotencyKey },
            query: { locationId, skuId }
          }
        });
      },

      async evaluateLowStock(locationId: string, skuId: string | undefined, idempotencyKey: string): Promise<EvaluateLowStockResponse> {
        const { data } = await api.POST("/v1/inventory/reorder-controls/evaluate", {
          params: {
            header: { "Idempotency-Key": idempotencyKey },
            query: { locationId, skuId }
          }
        });
        return data!;
      },

      async repriceStockLot(stockLotId: string, req: RepriceStockLotRequest, idempotencyKey: string): Promise<StockRepricingResponse> {
        const { data, error } = await api.POST("/v1/inventory/lots/{stockLotId}/reprice", {
          params: {
            path: { stockLotId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: req
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async repricings(locationId: string, skuId?: string): Promise<StockRepricingResponse[]> {
        const { data, error } = await api.GET("/v1/inventory/repricings", {
          params: { query: { locationId, skuId } }
        });
        if (error) {
          throw error;
        }
        return data ?? [];
      },

      async expiryFeed(
        locationId: string,
        windowDays?: number,
        tier?: "ALL" | "CRITICAL" | "WARNING" | "APPROACHING" | "EXPIRED"
      ): Promise<ExpiryAlertItem[]> {
        const { data, error } = await api.GET("/v1/inventory/locations/{locationId}/expiry-feed", {
          params: {
            path: { locationId },
            query: { windowDays, tier }
          }
        });
        if (error) {
          throw error;
        }
        return data ?? [];
      },

      async slowMovingStock(
        locationId: string,
        evaluationDays?: number,
        thresholdDaysOfSupply?: number
      ): Promise<SlowMovingStock[]> {
        const { data, error } = await api.GET("/v1/inventory/locations/{locationId}/slow-moving", {
          params: {
            path: { locationId },
            query: { evaluationDays, thresholdDaysOfSupply }
          }
        });
        if (error) {
          throw error;
        }
        return data ?? [];
      },

      async scanExpiryAlerts(locationId: string, idempotencyKey: string): Promise<ExpiryAlertScan> {
        const { data, error } = await api.POST("/v1/inventory/locations/{locationId}/expiry-alerts/scan", {
          params: {
            path: { locationId },
            header: { "Idempotency-Key": idempotencyKey }
          }
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async openStockTakeSession(
        req: OpenStockTakeSessionRequest,
        idempotencyKey: string
      ): Promise<StockTakeSessionResponse> {
        const { data, error } = await api.POST("/v1/inventory/stock-take/sessions", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: req
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async getActiveStockTakeSession(locationId: string): Promise<StockTakeSessionResponse | null> {
        const { data, error, response } = await api.GET("/v1/inventory/stock-take/sessions/active", {
          params: { query: { locationId } }
        });
        if (response?.status === 404) {
          return null;
        }
        if (error) {
          throw error;
        }
        return data ?? null;
      },

      async getStockTakeSession(sessionId: string): Promise<StockTakeSessionResponse> {
        const { data, error } = await api.GET("/v1/inventory/stock-take/sessions/{sessionId}", {
          params: { path: { sessionId } }
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async recordStockTakeScan(
        sessionId: string,
        req: StockTakeScanRequest,
        idempotencyKey: string
      ): Promise<StockTakeScanResponse> {
        const { data, error } = await api.POST("/v1/inventory/stock-take/sessions/{sessionId}/scan", {
          params: {
            path: { sessionId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: req
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async listStockTakeScans(sessionId: string): Promise<StockTakeScanItemResponse[]> {
        const { data, error } = await api.GET("/v1/inventory/stock-take/sessions/{sessionId}/scans", {
          params: { path: { sessionId } }
        });
        if (error) {
          throw error;
        }
        return data ?? [];
      },

      async batchStockTakeScan(
        sessionId: string,
        req: StockTakeBatchScanRequest,
        idempotencyKey: string
      ): Promise<StockTakeBatchScanResponse> {
        const { data, error } = await api.POST("/v1/inventory/stock-take/sessions/{sessionId}/scans/batch", {
          params: {
            path: { sessionId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: req
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async reviewStockTakeSession(sessionId: string): Promise<StockTakeReviewResponse> {
        const { data, error } = await api.GET("/v1/inventory/stock-take/sessions/{sessionId}/review", {
          params: { path: { sessionId } }
        });
        if (error) {
          throw error;
        }
        return data!;
      },

      async reconcileStockTakeSession(
        sessionId: string,
        req: StockTakeReconcileRequest,
        idempotencyKey: string
      ): Promise<StockTakeReconcileResponse> {
        const { data, error } = await api.POST("/v1/inventory/stock-take/sessions/{sessionId}/reconcile", {
          params: {
            path: { sessionId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: req
        });
        if (error) {
          throw error;
        }
        return data!;
      }
    }),
    [api, party, catalogue, trading]
  );
}