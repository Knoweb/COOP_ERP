import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Order } from "./tradingApi";
import { OrderPage } from "./OrderPage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const ORDER_ID = "0190f4cc-0000-7000-8000-000000000001";
const SKU = "0190f4bb-0000-7000-8000-000000000001";

const state = { entityId: SELLER, permissions: new Set<string>() };
let current: Order;

const api = {
  order: vi.fn(async () => current),
  sellerAvailability: vi.fn(async () => ({ [SKU]: 500 })),
  relationship: vi.fn(async () => ({ orderLockHoursBeforeEta: 48 })),
  submitOrder: vi.fn(async () => current),
  acceptOrder: vi.fn(async () => current),
  rejectOrder: vi.fn(async () => current),
  cancelOrder: vi.fn(async () => current),
  amendOrder: vi.fn(async () => ({ ...current, orderId: "0190f4cc-0000-7000-8000-000000000002" })),
  sku: vi.fn(async () => null),
  entity: vi.fn(async () => null),
  location: vi.fn(async () => null)
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));

function renderOrder() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/trading/orders/${ORDER_ID}`]}>
          <Routes>
            <Route path="/trading/orders/:orderId" element={<OrderPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the order card", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    current = {
      orderId: ORDER_ID,
      docNumber: "D101-ORD-0000001",
      status: "SUBMITTED",
      relationshipId: "0190f4dd-0000-7000-8000-000000000001",
      buyerEntityId: BUYER,
      sellerEntityId: SELLER,
      deliverToLocationId: "0190f0de-0000-7000-8000-000000000111",
      netAmount: 150000,
      lines: [{ lineId: "l1", lineNo: 1, skuId: SKU, uomCode: "EA", requestedQty: 120, cancelledQty: 0, indicativePrice: 1250 }]
    };
  });
  afterEach(cleanup);

  it("lets the seller accept a submitted order, showing what it can allocate", async () => {
    Object.assign(state, { entityId: SELLER, permissions: new Set(["ord.order.accept"]) });
    renderOrder();

    const accept = await screen.findByRole("button", { name: text("trading.order.accept") });
    expect(await screen.findByText("500")).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("trading.order.submit") })).toBeNull();
    fireEvent.click(accept);

    await waitFor(() => expect(api.acceptOrder).toHaveBeenCalledOnce());
    const [orderId, eta, overrides, key] = api.acceptOrder.mock.calls[0] as unknown as [string, string, unknown[], string];
    expect(orderId).toBe(ORDER_ID);
    expect(eta).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(overrides).toEqual([]);
    expect(key).toBeTruthy();
  }, 10000);

  it("asks the seller for a reason before a rejection", async () => {
    Object.assign(state, { entityId: SELLER, permissions: new Set(["ord.order.accept"]) });
    renderOrder();

    fireEvent.click(await screen.findByRole("button", { name: text("trading.order.reject") }));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "NO_STOCK" } });
    fireEvent.click(screen.getByRole("button", { name: text("shell.reason.confirm") }));

    await waitFor(() => expect(api.rejectOrder).toHaveBeenCalledOnce());
    expect(api.rejectOrder.mock.calls[0]).toEqual([ORDER_ID, "NO_STOCK", null, expect.any(String)]);
  });

  it("gives the buyer the submit of a draft and no decision", async () => {
    current = { ...current, status: "DRAFT", docNumber: undefined };
    Object.assign(state, { entityId: BUYER, permissions: new Set(["ord.order.submit", "ord.order.accept"]) });
    renderOrder();

    fireEvent.click(await screen.findByRole("button", { name: text("trading.order.submit") }));
    await waitFor(() => expect(api.submitOrder).toHaveBeenCalledOnce());
    expect(screen.queryByRole("button", { name: text("trading.order.accept") })).toBeNull();
    expect(api.sellerAvailability).not.toHaveBeenCalled();
  });

  it("names the buyer's delivery point to the seller from the order, without reading the buyer's location", async () => {
    current = {
      ...current,
      deliverTo: { code: "W01", nameEn: "Kurunegala warehouse", nameSi: "කුරුණෑගල ගබඩාව" }
    };
    Object.assign(state, { entityId: SELLER, permissions: new Set<string>() });
    renderOrder();

    expect(await screen.findByText("W01 Kurunegala warehouse")).toBeTruthy();
    expect(screen.queryByText("000111")).toBeNull();
    // The seller may not read the buyer's locations (CR-24A-2): asking would only be refused (404).
    expect(api.location).not.toHaveBeenCalled();
  });

  it("shows a counterparty's location it cannot name as a dash, not as a short id, and does not ask for it", async () => {
    Object.assign(state, { entityId: SELLER, permissions: new Set<string>() });
    renderOrder();

    await screen.findByText("D101-ORD-0000001");
    expect(screen.queryByText("000111")).toBeNull();
    expect(api.location).not.toHaveBeenCalled();
  });

  it("lets the buyer amend a submitted order: the quantities and the delivery date, the whole set sent", async () => {
    Object.assign(state, { entityId: BUYER, permissions: new Set(["ord.order.submit"]) });
    renderOrder();

    fireEvent.click(await screen.findByRole("button", { name: text("trading.order.amend") }));
    fireEvent.change(screen.getByRole("spinbutton", { name: "Quantity of line 1" }), { target: { value: "80" } });
    fireEvent.change(screen.getByLabelText(text("trading.order.amend.eta")), { target: { value: "2099-01-15" } });
    fireEvent.click(screen.getByRole("button", { name: text("trading.order.amend.save") }));

    await waitFor(() => expect(api.amendOrder).toHaveBeenCalledOnce());
    expect(api.amendOrder.mock.calls[0]).toEqual([
      ORDER_ID,
      { requestedEta: "2099-01-15", notes: undefined, lines: [{ skuId: SKU, uomCode: "EA", qty: 80 }] },
      expect.any(String)
    ]);
  });

  it("offers no amendment of an accepted order, nor to the seller", async () => {
    current = { ...current, status: "ACCEPTED", lines: [{ ...current.lines[0], allocatedQty: 120, fulfilledQty: 0 }] };
    Object.assign(state, { entityId: BUYER, permissions: new Set(["ord.order.submit"]) });
    renderOrder();
    await screen.findByText("D101-ORD-0000001");
    expect(screen.queryByRole("button", { name: text("trading.order.amend") })).toBeNull();
  });

  it("shows the allocation and the tier price once accepted, and leads the seller to the delivery note", async () => {
    current = {
      ...current,
      status: "ACCEPTED",
      committedEta: "2026-09-30",
      lines: [{ ...current.lines[0], allocatedQty: 120, fulfilledQty: 0, tierPrice: 1200 }]
    };
    Object.assign(state, { entityId: SELLER, permissions: new Set(["ord.order.accept", "del.note.draft"]) });
    renderOrder();

    expect(await screen.findByRole("columnheader", { name: text("trading.column.allocated") })).toBeTruthy();
    const link = screen.getByRole("link", { name: text("trading.note.new") });
    expect(link.getAttribute("href")).toBe(`/trading/delivery-notes/new?orderId=${ORDER_ID}`);
  });
});
