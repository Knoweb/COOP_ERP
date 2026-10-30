// The calls of the reporting module (M8), through the shell's API client and typed by the client
// generated from openapi/m8reporting.yaml. The locations of the stock position's filter come
// from M1's published list.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m8reporting";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";
import type { components as catalogueComponents, paths as cataloguePaths } from "../../generated/m2catalogue";

export type Dashboard = components["schemas"]["DashboardResponse"];
export type Tile = components["schemas"]["DashboardTile"];
export type ReportDefinition = components["schemas"]["ReportDefinitionResponse"];
export type ReportData = components["schemas"]["ReportDataResponse"];
export type ReportRun = components["schemas"]["ReportRunResponse"];
export type ExceptionItem = components["schemas"]["ExceptionItemResponse"];
export type Location = partyComponents["schemas"]["LocationResponse"];
export type ShopTarget = components["schemas"]["ShopTargetResponse"];
export type ShopTargetPerformanceItem = components["schemas"]["ShopTargetPerformanceItem"];
export type ShopTargetPerformanceReport = components["schemas"]["ShopTargetPerformanceResponse"];
export type CreateShopTargetRequest = components["schemas"]["CreateShopTargetRequest"];
export type UpdateShopTargetRequest = components["schemas"]["UpdateShopTargetRequest"];
export type DailyShopReport = components["schemas"]["DailyShopReportResponse"];
export type DailyShopReportSummary = components["schemas"]["DailyShopReportSummary"];
export type DailyShopReportItem = components["schemas"]["DailyShopReportItem"];
export type MonthlyShopReport = components["schemas"]["MonthlyShopReportResponse"];
export type MonthlyShopReportSummary = components["schemas"]["MonthlyShopReportSummary"];
export type MonthlyDailyTrendItem = components["schemas"]["MonthlyDailyTrendItem"];
export type MonthlyShopReportItem = components["schemas"]["MonthlyShopReportItem"];
export type ProfitCenter = components["schemas"]["ProfitCenterResponse"];
export type CreateProfitCenterRequest = components["schemas"]["CreateProfitCenterRequest"];
export type ProfitCenterPnlItem = components["schemas"]["ProfitCenterPnlItem"];
export type ProfitCenterPnlSummary = components["schemas"]["ProfitCenterPnlSummary"];
export type MonthlyProfitCenterPnlReport = components["schemas"]["MonthlyProfitCenterPnlResponse"];
export type Asset = components["schemas"]["AssetResponse"];
export type RegisterAssetRequest = components["schemas"]["RegisterAssetRequest"];
export type RevenueEstimateRequest = components["schemas"]["RevenueEstimateRequest"];
export type RevenueEstimateResponse = components["schemas"]["RevenueEstimateResponse"];
export type RevenueEstimateItemResponse = components["schemas"]["RevenueEstimateItemResponse"];
export type RevenueEstimateAssumptions = components["schemas"]["RevenueEstimateAssumptions"];
export type Sku = catalogueComponents["schemas"]["SkuResponse"];
export type DebtorsReport = components["schemas"]["DebtorsReportResponse"];
export type DebtorSummaryItem = components["schemas"]["DebtorSummaryItem"];
export type CreditorsReport = components["schemas"]["CreditorsReportResponse"];
export type CreditorSummaryItem = components["schemas"]["CreditorSummaryItem"];
export type OpeningBalancesReport = components["schemas"]["OpeningBalancesReportResponse"];
export type OpeningBalanceItem = components["schemas"]["OpeningBalanceItem"];
export type GoodsSuppliedReport = components["schemas"]["GoodsSuppliedReportResponse"];
export type GoodsSuppliedLineItem = components["schemas"]["GoodsSuppliedLineItem"];
export type CreditVoucher = components["schemas"]["CreditVoucherResponse"];
export type CreditVoucherLine = components["schemas"]["CreditVoucherLineResponse"];
export type CreditVoucherPrint = components["schemas"]["CreditVoucherPrintResponse"];

/** The parameters of a report: a period for the period reports, a location for the stock position. */
export type ReportQuery = { from?: string; to?: string; locationId?: string };

export function useReportingApi() {
  const api = useApiClient<paths>();
  const party = useApiClient<partyPaths>();
  const catalogue = useApiClient<cataloguePaths>();

  return useMemo(
    () => ({
      async dashboard(): Promise<Dashboard> {
        const { data } = await api.GET("/v1/reporting/dashboard");
        return data!;
      },

      async definitions(): Promise<ReportDefinition[]> {
        const { data } = await api.GET("/v1/reporting/reports");
        return data ?? [];
      },

      async data(reportId: string, query: ReportQuery): Promise<ReportData> {
        const { data } = await api.GET("/v1/reporting/reports/{reportId}/data", {
          params: { path: { reportId }, query }
        });
        return data!;
      },

      /** The CSV as text: the browser cannot follow a link that needs the bearer token. */
      async csv(reportId: string, query: ReportQuery): Promise<string> {
        const { data } = await api.GET("/v1/reporting/reports/{reportId}/csv", {
          params: { path: { reportId }, query },
          parseAs: "text"
        });
        return data ?? "";
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async requestRun(
        reportId: string,
        query: ReportQuery,
        language: "en" | "si" | "ta",
        idempotencyKey: string
      ): Promise<ReportRun> {
        const { data } = await api.POST("/v1/reporting/reports/{reportId}/runs", {
          params: { path: { reportId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { ...query, language }
        });
        return data!;
      },

      async exceptions(): Promise<ExceptionItem[]> {
        const { data } = await api.GET("/v1/reporting/exceptions");
        return data ?? [];
      },

      async runs(reportId: string): Promise<ReportRun[]> {
        const { data } = await api.GET("/v1/reporting/reports/{reportId}/runs", {
          params: { path: { reportId } }
        });
        return data ?? [];
      },

      async run(runId: string): Promise<ReportRun> {
        const { data } = await api.GET("/v1/reporting/runs/{runId}", { params: { path: { runId } } });
        return data!;
      },

      async locations(): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      },

      async createShopTarget(
        body: CreateShopTargetRequest,
        idempotencyKey: string
      ): Promise<ShopTarget> {
        const { data } = await api.POST("/v1/reporting/shop-targets", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async updateShopTarget(
        targetId: string,
        body: UpdateShopTargetRequest,
        idempotencyKey: string
      ): Promise<ShopTarget> {
        const { data } = await api.PUT("/v1/reporting/shop-targets/{targetId}", {
          params: { path: { targetId }, header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async getShopTarget(targetId: string): Promise<ShopTarget> {
        const { data } = await api.GET("/v1/reporting/shop-targets/{targetId}", {
          params: { path: { targetId } }
        });
        return data!;
      },

      async getShopTargetPerformance(query?: {
        locationId?: string;
        from?: string;
        to?: string;
      }): Promise<ShopTargetPerformanceReport> {
        const { data } = await api.GET("/v1/reporting/shop-targets/performance", {
          params: { query }
        });
        return data ?? { items: [] };
      },

      async dailyShopReport(query?: {
        date?: string;
        locationId?: string;
      }): Promise<DailyShopReport> {
        const { data } = await api.GET("/v1/reporting/shop-reports/daily", {
          params: { query }
        });
        return data!;
      },

      async monthlyShopReport(query?: {
        yearMonth?: string;
        locationId?: string;
      }): Promise<MonthlyShopReport> {
        const { data } = await api.GET("/v1/reporting/shop-reports/monthly", {
          params: { query }
        });
        return data!;
      },

      async listProfitCenters(): Promise<ProfitCenter[]> {
        const { data } = await api.GET("/v1/reporting/profit-centers");
        return data?.items ?? [];
      },

      async createProfitCenter(
        body: CreateProfitCenterRequest,
        idempotencyKey: string
      ): Promise<ProfitCenter> {
        const { data } = await api.POST("/v1/reporting/profit-centers", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async monthlyProfitCenterPnl(query?: {
        month?: string;
        profitCenterId?: string;
      }): Promise<MonthlyProfitCenterPnlReport> {
        const { data } = await api.GET("/v1/reporting/profit-centers/monthly-pnl", {
          params: { query }
        });
        return data!;
      },

      async listAssets(query?: {
        locationId?: string;
        assetType?: string;
        status?: string;
      }): Promise<Asset[]> {
        const { data } = await api.GET("/v1/reporting/assets", {
          params: { query }
        });
        return data?.items ?? [];
      },

      async getAsset(assetId: string): Promise<Asset> {
        const { data } = await api.GET("/v1/reporting/assets/{assetId}", {
          params: { path: { assetId } }
        });
        return data!;
      },

      async registerAsset(
        body: RegisterAssetRequest,
        idempotencyKey: string
      ): Promise<Asset> {
        const { data } = await api.POST("/v1/reporting/assets", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async searchSkus(q?: string): Promise<Sku[]> {
        const { data } = await catalogue.GET("/v1/catalogue/skus", {
          params: { query: { q: q || undefined, limit: 50 } }
        });
        return data?.items ?? [];
      },

      async estimateRevenue(
        body: RevenueEstimateRequest,
        idempotencyKey: string
      ): Promise<RevenueEstimateResponse> {
        const { data } = await api.POST("/v1/reporting/revenue-estimates", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body
        });
        return data!;
      },

      async listDebtorsReport(asOf?: string): Promise<DebtorsReport> {
        const { data } = await api.GET("/v1/reporting/finance/debtors", {
          params: { query: { asOf } }
        });
        return data!;
      },

      async exportDebtorsCsv(asOf?: string): Promise<string> {
        const { data } = await api.GET("/v1/reporting/finance/debtors/csv", {
          params: { query: { asOf } },
          parseAs: "text"
        });
        return data as string;
      },

      async listCreditorsReport(asOf?: string): Promise<CreditorsReport> {
        const { data } = await api.GET("/v1/reporting/finance/creditors", {
          params: { query: { asOf } }
        });
        return data!;
      },

      async exportCreditorsCsv(asOf?: string): Promise<string> {
        const { data } = await api.GET("/v1/reporting/finance/creditors/csv", {
          params: { query: { asOf } },
          parseAs: "text"
        });
        return data as string;
      },

      async listOpeningBalancesReport(counterpartyId?: string): Promise<OpeningBalancesReport> {
        const { data } = await api.GET("/v1/reporting/finance/opening-balances", {
          params: { query: { counterpartyId } }
        });
        return data!;
      },

      async exportOpeningBalancesCsv(counterpartyId?: string): Promise<string> {
        const { data } = await api.GET("/v1/reporting/finance/opening-balances/csv", {
          params: { query: { counterpartyId } },
          parseAs: "text"
        });
        return data as string;
      },

      async getGoodsSuppliedReport(query?: {
        from?: string;
        to?: string;
        societyId?: string;
        locationId?: string;
      }): Promise<GoodsSuppliedReport> {
        const { data } = await api.GET("/v1/reporting/finance/goods-supplied", {
          params: { query }
        });
        return data!;
      },

      async exportGoodsSuppliedCsv(query?: {
        from?: string;
        to?: string;
        societyId?: string;
        locationId?: string;
      }): Promise<string> {
        const { data } = await api.GET("/v1/reporting/finance/goods-supplied/csv", {
          params: { query },
          parseAs: "text"
        });
        return data as string;
      },

      async getCreditVoucher(invoiceId: string): Promise<CreditVoucher> {
        const { data } = await api.GET("/v1/reporting/finance/credit-vouchers/{invoiceId}", {
          params: { path: { invoiceId } }
        });
        return data!;
      },

      async printCreditVoucher(invoiceId: string): Promise<CreditVoucherPrint> {
        const { data } = await api.GET("/v1/reporting/finance/credit-vouchers/{invoiceId}/print", {
          params: { path: { invoiceId } }
        });
        return data!;
      }
    }),
    [api, party, catalogue]
  );
}
