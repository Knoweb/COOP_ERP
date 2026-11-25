import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type {
  Claim,
  DeliveryNote,
  Discrepancy,
  Entity,
  Exposure,
  Grn,
  Invoice,
  Order,
  PaymentReceipt
} from "./tradingApi";
import { TradingPage } from "./TradingPage";

const state = { permissions: new Set<string>() };

const api = {
  orders: vi.fn(async (): Promise<Order[]> => []),
  deliveryNotes: vi.fn(async (): Promise<DeliveryNote[]> => []),
  grns: vi.fn(async (): Promise<Grn[]> => []),
  discrepancies: vi.fn(async (): Promise<Discrepancy[]> => []),
  claims: vi.fn(async (): Promise<Claim[]> => []),
  payments: vi.fn(async (): Promise<PaymentReceipt[]> => []),
  exposures: vi.fn(async (): Promise<Exposure[]> => []),
  invoices: vi.fn(async (): Promise<Invoice[]> => []),
  entity: vi.fn(async (): Promise<Entity | null> => null)
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));
vi.mock("../../shell/scope/useScope", () => ({
  useScope: () => ({ entityId: "0190f000-0000-7000-8000-000000000001", policyClass: "OWN", locationIds: [] })
}));

const text = (id: string) => (messages.en as Record<string, string>)[id];

function renderTradingPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/trading"]}>
          <TradingPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("TradingPage", () => {
  beforeEach(() => {
    state.permissions = new Set();
    vi.clearAllMocks();
    api.orders.mockResolvedValue([]);
    api.deliveryNotes.mockResolvedValue([]);
    api.grns.mockResolvedValue([]);
    api.discrepancies.mockResolvedValue([]);
    api.claims.mockResolvedValue([]);
    api.payments.mockResolvedValue([]);
    api.exposures.mockResolvedValue([]);
    api.invoices.mockResolvedValue([]);
    api.entity.mockResolvedValue(null);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders register cards and empty states cleanly when no extra permissions are held", async () => {
    renderTradingPage();

    expect(screen.getByRole("heading", { level: 1, name: text("trading.title") })).toBeTruthy();
    expect(screen.queryByRole("link", { name: text("trading.order.new") })).toBeNull();
    expect(screen.getByRole("heading", { level: 2, name: text("trading.invoices.received.title") })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: text("trading.payments.made.title") })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: text("trading.accounts.sellers.title") })).toBeTruthy();

    await waitFor(() => {
      expect(screen.getByText(text("trading.invoices.empty"))).toBeTruthy();
      expect(screen.getByText(text("trading.payments.empty"))).toBeTruthy();
      expect(screen.getByText(text("trading.accounts.empty"))).toBeTruthy();
    });
  });

  it("renders New order action in the header and shows populated requisition rows when permitted", async () => {
    state.permissions = new Set(["ord.order.draft"]);
    api.orders.mockResolvedValue([
      {
        orderId: "0190f4cc-0000-7000-8000-000000000001",
        docNumber: "D101-ORD-0000001",
        status: "SUBMITTED",
        relationshipId: "0190f4dd-0000-7000-8000-000000000001",
        buyerEntityId: "0190f0de-0000-7000-8000-0000000000e1",
        sellerEntityId: "0190f000-0000-7000-8000-000000000001",
        netAmount: 150000,
        lines: []
      }
    ]);

    renderTradingPage();

    const newOrderLink = screen.getByRole("link", { name: text("trading.order.new") });
    expect(newOrderLink.getAttribute("href")).toBe("/trading/orders/new");

    await waitFor(() => {
      const docLink = screen.getByRole("link", { name: "D101-ORD-0000001" });
      expect(docLink.getAttribute("href")).toBe("/trading/orders/0190f4cc-0000-7000-8000-000000000001");
    });
  });

  it("keeps each register heading directly inside the section containing its document links", async () => {
    state.permissions = new Set(["del.note.draft"]);
    api.deliveryNotes.mockResolvedValue([
      {
        deliveryNoteId: "0190f4ef-0000-7000-8000-000000000001",
        docNumber: "FED-DN-0000001",
        status: "IN_TRANSIT",
        buyerEntityId: "0190f0de-0000-7000-8000-0000000000e1",
        sellerEntityId: "0190f000-0000-7000-8000-000000000001"
      } as unknown as DeliveryNote
    ]);

    renderTradingPage();

    const heading = screen.getByRole("heading", { level: 2, name: text("trading.notes.title") });
    await waitFor(() => {
      const link = heading.parentElement?.querySelector('a[href^="/trading/delivery-notes/"]');
      expect(link?.getAttribute("href")).toBe("/trading/delivery-notes/0190f4ef-0000-7000-8000-000000000001");
    });
  });
});
