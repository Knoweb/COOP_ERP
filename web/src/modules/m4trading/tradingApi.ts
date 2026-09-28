// The calls of the trading screens (M4-11), through the shell's API client and typed by the
// clients generated from openapi/m4trading.yaml. The names of the parties, places, items and
// batches come from the modules that own them (M1, M2), and the stock from M5, never from M4.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m4trading";
import type { components as partyComponents, paths as partyPaths } from "../../generated/m1party";
import type { components as catalogueComponents, paths as cataloguePaths } from "../../generated/m2catalogue";
import type { components as pricingComponents, paths as pricingPaths } from "../../generated/m3pricing";
import type { components as inventoryComponents, paths as inventoryPaths } from "../../generated/m5inventory";

export type Order = components["schemas"]["OrderResponse"];
export type DeliveryPoint = components["schemas"]["DeliveryPointResponse"];
export type OrderLine = components["schemas"]["OrderLineResponse"];
export type OrderStatus = components["schemas"]["OrderStatus"];
export type CreateOrderRequest = components["schemas"]["CreateOrderRequest"];
export type AllocationOverride = components["schemas"]["AllocationOverride"];
export type DeliveryNote = components["schemas"]["DeliveryNoteResponse"];
export type CreateDeliveryNoteRequest = components["schemas"]["CreateDeliveryNoteRequest"];
export type Grn = components["schemas"]["GrnResponse"];
export type CaptureGrnRequest = components["schemas"]["CaptureGrnRequest"];
export type Invoice = components["schemas"]["InvoiceResponse"];
export type CreditNote = components["schemas"]["CreditNoteResponse"];
export type Discrepancy = components["schemas"]["DiscrepancyResponse"];
export type Relationship =partyComponents["schemas"]["RelationshipResponse"];
export type Entity = partyComponents["schemas"]["EntityResponse"];
export type Location = partyComponents["schemas"]["LocationResponse"];
export type Sku = catalogueComponents["schemas"]["SkuResponse"];
export type Batch = catalogueComponents["schemas"]["BatchResponse"];
export type TradePrice = pricingComponents["schemas"]["TradePriceResponse"];
export type LotBalance = inventoryComponents["schemas"]["LotBalanceResponse"];
export type Movement = inventoryComponents["schemas"]["MovementResponse"];

export type Side = "BUYER" | "SELLER";

export function useTradingApi() {
  const api = useApiClient<paths>();
  const party = useApiClient<partyPaths>();
  const catalogue = useApiClient<cataloguePaths>();
  const pricing = useApiClient<pricingPaths>();
  const inventory = useApiClient<inventoryPaths>();

  return useMemo(
    () => ({
      // ---- reads -------------------------------------------------------------------------

      async orders(role: Side): Promise<Order[]> {
        const { data } = await api.GET("/v1/trading/orders", { params: { query: { role } } });
        return data ?? [];
      },

      async order(orderId: string): Promise<Order> {
        const { data } = await api.GET("/v1/trading/orders/{orderId}", { params: { path: { orderId } } });
        return data!;
      },

      /** What the caller, as the seller, can allocate of each item today (0 when asked by anyone else). */
      async sellerAvailability(sellerId: string, skuIds: string[]): Promise<Record<string, number>> {
        if (skuIds.length === 0) {
          return {};
        }
        const { data } = await api.GET("/v1/trading/orders/availability", { params: { query: { sellerId, skuIds } } });
        return Object.fromEntries((data ?? []).map((row) => [row.skuId, row.availableQty]));
      },

      async deliveryNotes(role: Side): Promise<DeliveryNote[]> {
        const { data } = await api.GET("/v1/trading/delivery-notes", { params: { query: { role } } });
        return data ?? [];
      },

      async deliveryNote(deliveryNoteId: string): Promise<DeliveryNote> {
        const { data } = await api.GET("/v1/trading/delivery-notes/{deliveryNoteId}", {
          params: { path: { deliveryNoteId } }
        });
        return data!;
      },

      async grns(role: Side): Promise<Grn[]> {
        const { data } = await api.GET("/v1/trading/grns", { params: { query: { role } } });
        return data ?? [];
      },

      async grn(grnId: string): Promise<Grn> {
        const { data } = await api.GET("/v1/trading/grns/{grnId}", { params: { path: { grnId } } });
        return data!;
      },

      async invoices(role: Side): Promise<Invoice[]> {
        const { data } = await api.GET("/v1/trading/invoices", { params: { query: { role } } });
        return data ?? [];
      },

      async invoice(invoiceId: string): Promise<Invoice> {
        const { data } = await api.GET("/v1/trading/invoices/{invoiceId}", { params: { path: { invoiceId } } });
        return data!;
      },

      /** A fresh link to the A4 PDF of the invoice, for its seller or its buyer; refused until the worker printed it. */
      async invoicePrint(invoiceId: string): Promise<string> {
        const { data } = await api.GET("/v1/trading/invoices/{invoiceId}/print", { params: { path: { invoiceId } } });
        return data!.url;
      },

      async discrepancies(role: Side): Promise<Discrepancy[]> {
        const { data } = await api.GET("/v1/trading/discrepancies", { params: { query: { role } } });
        return data ?? [];
      },

      async discrepancy(discrepancyId: string): Promise<Discrepancy> {
        const { data } = await api.GET("/v1/trading/discrepancies/{discrepancyId}", {
          params: { path: { discrepancyId } }
        });
        return data!;
      },

      async creditNote(creditNoteId: string): Promise<CreditNote> {
        const { data } = await api.GET("/v1/trading/credit-notes/{creditNoteId}", {
          params: { path: { creditNoteId } }
        });
        return data!;
      },

      /** A fresh link to the A4 PDF of the credit note, for its seller or its buyer; refused until the worker printed it. */
      async creditNotePrint(creditNoteId: string): Promise<string> {
        const { data } = await api.GET("/v1/trading/credit-notes/{creditNoteId}/print", {
          params: { path: { creditNoteId } }
        });
        return data!.url;
      },

      /** The ACTIVE relationships in which the caller's entity buys: the sellers it can order from. */
      async sellers(): Promise<Relationship[]> {
        const { data } = await party.GET("/v1/party/relationships", { params: { query: { side: "BUYER" } } });
        return (data ?? []).filter((row) => row.status === "ACTIVE");
      },

      async relationship(relationshipId: string): Promise<Relationship | null> {
        const { data } = await party.GET("/v1/party/relationships/{relationshipId}", {
          params: { path: { relationshipId } }
        });
        return data ?? null;
      },

      async entity(entityId: string): Promise<Entity | null> {
        const { data } = await party.GET("/v1/party/entities/{entityId}", { params: { path: { entityId } } });
        return data ?? null;
      },

      /** The caller's own active locations, of one type or all. */
      async locations(locationType?: "WAREHOUSE" | "SHOP"): Promise<Location[]> {
        const { data } = await party.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return (data?.items ?? []).filter(
          (location) => location.status === "ACTIVE" && (!locationType || location.locationType === locationType)
        );
      },

      async location(locationId: string): Promise<Location | null> {
        const { data } = await party.GET("/v1/party/locations/{locationId}", { params: { path: { locationId } } });
        return data ?? null;
      },

      async searchSkus(q: string): Promise<Sku[]> {
        const { data } = await catalogue.GET("/v1/catalogue/skus", { params: { query: { q, limit: 20 } } });
        return (data?.items ?? []).filter((sku) => sku.status === "SHARED" || sku.status === "LOCAL");
      },

      async sku(skuId: string): Promise<Sku | null> {
        const { data } = await catalogue.GET("/v1/catalogue/skus/{skuId}", { params: { path: { skuId } } });
        return data ?? null;
      },

      async batch(batchId: string): Promise<Batch | null> {
        const { data } = await catalogue.GET("/v1/catalogue/batches/{batchId}", { params: { path: { batchId } } });
        return data ?? null;
      },

      /** The tier price of a line under the relationship today, or null when the list has none. */
      async tradePrice(relationshipId: string, skuId: string, uom: string, qty: number, date: string): Promise<TradePrice | null> {
        try {
          const { data } = await pricing.GET("/v1/pricing/resolve/trade", {
            params: { query: { relationshipId, skuId, uom, qty, date } }
          });
          return data ?? null;
        } catch {
          return null;
        }
      },

      async balances(locationId: string): Promise<LotBalance[]> {
        const { data } = await inventory.GET("/v1/inventory/locations/{locationId}/balances", {
          params: { path: { locationId } }
        });
        return data ?? [];
      },

      /** The movements a confirmed GRN put into the receiver's stock (empty until M5 applied it). */
      async receipt(grnId: string): Promise<Movement[]> {
        const { data } = await inventory.GET("/v1/inventory/receipts/{grnId}", { params: { path: { grnId } } });
        return data ?? [];
      },

      // ---- commands: `key` comes from useIdempotencyKey(), one per user action ------------

      async createOrder(body: CreateOrderRequest, key: string): Promise<Order> {
        const { data } = await api.POST("/v1/trading/orders", { params: { header: { "Idempotency-Key": key } }, body });
        return data!;
      },

      async submitOrder(orderId: string, key: string): Promise<Order> {
        const { data } = await api.POST("/v1/trading/orders/{orderId}/submit", {
          params: { path: { orderId }, header: { "Idempotency-Key": key } }
        });
        return data!;
      },

      async cancelOrder(orderId: string, reasonCode: string, reasonText: string | null, key: string): Promise<Order> {
        const { data } = await api.POST("/v1/trading/orders/{orderId}/cancel", {
          params: { path: { orderId }, header: { "Idempotency-Key": key } },
          body: { reasonCode, reasonText: reasonText ?? undefined }
        });
        return data!;
      },

      async acceptOrder(orderId: string, committedEta: string, overrides: AllocationOverride[], key: string): Promise<Order> {
        const { data } = await api.POST("/v1/trading/orders/{orderId}/accept", {
          params: { path: { orderId }, header: { "Idempotency-Key": key } },
          body: { committedEta, overrides: overrides.length > 0 ? overrides : undefined }
        });
        return data!;
      },

      async rejectOrder(orderId: string, reasonCode: string, reasonText: string | null, key: string): Promise<Order> {
        const { data } = await api.POST("/v1/trading/orders/{orderId}/reject", {
          params: { path: { orderId }, header: { "Idempotency-Key": key } },
          body: { reasonCode, reasonText: reasonText ?? undefined }
        });
        return data!;
      },

      async createDeliveryNote(body: CreateDeliveryNoteRequest, key: string): Promise<DeliveryNote> {
        const { data } = await api.POST("/v1/trading/delivery-notes", {
          params: { header: { "Idempotency-Key": key } },
          body
        });
        return data!;
      },

      async issueDeliveryNote(deliveryNoteId: string, key: string): Promise<DeliveryNote> {
        const { data } = await api.POST("/v1/trading/delivery-notes/{deliveryNoteId}/issue", {
          params: { path: { deliveryNoteId }, header: { "Idempotency-Key": key } }
        });
        return data!;
      },

      async dispatchDeliveryNote(deliveryNoteId: string, vehicleRef: string, driverName: string, key: string): Promise<DeliveryNote> {
        const { data } = await api.POST("/v1/trading/delivery-notes/{deliveryNoteId}/dispatch", {
          params: { path: { deliveryNoteId }, header: { "Idempotency-Key": key } },
          body: { vehicleRef, driverName }
        });
        return data!;
      },

      async captureGrn(body: CaptureGrnRequest, key: string): Promise<Grn> {
        const { data } = await api.POST("/v1/trading/grns", { params: { header: { "Idempotency-Key": key } }, body });
        return data!;
      },

      async issueInvoice(grnIds: string[], key: string): Promise<Invoice> {
        const { data } = await api.POST("/v1/trading/invoices", {
          params: { header: { "Idempotency-Key": key } },
          body: { grnIds }
        });
        return data!;
      },

      /**
       * The seller accepts the buyer's count. A short quantity was never billed and is settled with no
       * money; damaged quantity the invoice charged is credited by a credit note issued with it.
       */
      async settleDiscrepancy(discrepancyId: string, reason: string, key: string): Promise<Discrepancy> {
        const { data } = await api.POST("/v1/trading/discrepancies/{discrepancyId}/settle", {
          params: { path: { discrepancyId }, header: { "Idempotency-Key": key } },
          body: { reason }
        });
        return data!;
      },

      async disputeInvoice(invoiceId: string, reason: string, key: string): Promise<Invoice> {
        const { data } = await api.POST("/v1/trading/invoices/{invoiceId}/dispute", {
          params: { path: { invoiceId }, header: { "Idempotency-Key": key } },
          body: { reason }
        });
        return data!;
      },

      async resolveInvoiceDispute(invoiceId: string, key: string): Promise<Invoice> {
        const { data } = await api.POST("/v1/trading/invoices/{invoiceId}/resolve-dispute", {
          params: { path: { invoiceId }, header: { "Idempotency-Key": key } },
          body: {}
        });
        return data!;
      },

      async confirmGrn(grnId: string, key: string): Promise<Grn> {
        const { data } = await api.POST("/v1/trading/grns/{grnId}/confirm", {
          params: { path: { grnId }, header: { "Idempotency-Key": key } }
        });
        return data!;
      }
    }),
    [api, party, catalogue, pricing, inventory]
  );
}
