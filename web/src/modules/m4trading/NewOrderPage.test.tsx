import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { NewOrderPage } from "./NewOrderPage";
import type { Location, Relationship, Sku } from "./tradingApi";

const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const SELLER = "0190f000-0000-7000-8000-000000000001";
const RELATIONSHIP_ID = "0190f4dd-0000-7000-8000-000000000001";
const MANUAL_BRANCH = "0190f0de-0000-7000-8000-000000000111";
const AUTO_BRANCH = "0190f0de-0000-7000-8000-000000000222";
const SKU_ID = "0190f4bb-0000-7000-8000-000000000001";

const mockSellers = [
  {
    relationshipId: RELATIONSHIP_ID,
    sellerEntityId: SELLER,
    buyerEntityId: BUYER,
    status: "ACTIVE" as const,
    orderLockHoursBeforeEta: 48,
    discrepancyWindowDays: 7,
    allocationRule: "COMMITTED_ONLY",
    effectiveFrom: "2026-01-01"
  }
] as unknown as Relationship[];

const mockLocations = [
  {
    locationId: AUTO_BRANCH,
    ownerEntityId: BUYER,
    locationCode: "S1",
    nameEn: "Shop 1 Online",
    nameSi: "සාප්පුව 1",
    nameTa: "கடை 1",
    locationType: "SHOP" as const,
    status: "ACTIVE" as const,
    connectivitySpecMet: true
  },
  {
    locationId: MANUAL_BRANCH,
    ownerEntityId: BUYER,
    locationCode: "MB1",
    nameEn: "Manual Branch Rural",
    nameSi: "ග්‍රාමීය අතින් ශාඛාව",
    nameTa: "கிராமிய கையேடு கிளை",
    locationType: "SHOP" as const,
    status: "ACTIVE" as const,
    connectivitySpecMet: false
  }
] as unknown as Location[];

const mockSku = {
  skuId: SKU_ID,
  skuCode: "RICE-01",
  nameEn: "Samba Rice",
  nameSi: "සම්බා සහල්",
  nameTa: "சம்பா அரிசி",
  baseUomCode: "KG",
  status: "SHARED" as const
} as unknown as Sku;

const api = {
  sellers: vi.fn(async () => mockSellers),
  locations: vi.fn(async () => mockLocations),
  searchSkus: vi.fn(async () => [mockSku]),
  tradePrice: vi.fn(async () => ({ unitPrice: 220 })),
  createOrder: vi.fn(async () => ({
    orderId: "0190f4cc-0000-7000-8000-000000000099",
    status: "DRAFT"
  })),
  submitOrder: vi.fn(async () => ({
    orderId: "0190f4cc-0000-7000-8000-000000000099",
    status: "SUBMITTED"
  })),
  entity: vi.fn(async () => ({ entityId: SELLER, nameEn: "Apex Wholesalers" }))
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: BUYER }) }));

function renderNewOrder() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/trading/orders/new"]}>
          <Routes>
            <Route path="/trading/orders/new" element={<NewOrderPage />} />
            <Route path="/trading/orders/:orderId" element={<div data-testid="order-detail" />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("NewOrderPage - Manual Branch On-Behalf Ordering (M3-10)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(cleanup);

  it("identifies non-computerized branches and displays the on-behalf notice when selected", async () => {
    renderNewOrder();

    // Verify manual branch has the [Manual branch] indicator in dropdown
    const deliverSelect = await screen.findByLabelText(text("trading.field.deliver_to"));
    expect(deliverSelect).toBeTruthy();

    const manualOption = await screen.findByRole("option", { name: /Manual Branch Rural \[Manual branch\]/i });
    expect(manualOption).toBeTruthy();

    // When manual branch is selected, on-behalf notice is displayed
    fireEvent.change(deliverSelect, { target: { value: MANUAL_BRANCH } });

    expect(await screen.findByRole("status")).toBeTruthy();
    expect(screen.getByText(/On-behalf order/i)).toBeTruthy();
    expect(screen.getByText(/Entering order on behalf of non-computerized branch/i)).toBeTruthy();
  });

  it("allows submitting an order on behalf of a manual branch", async () => {
    renderNewOrder();

    const sellerSelect = await screen.findByLabelText(text("trading.field.seller"));
    await waitFor(() => expect(sellerSelect.children.length).toBeGreaterThan(1));
    fireEvent.change(sellerSelect, { target: { value: RELATIONSHIP_ID } });

    const deliverSelect = await screen.findByLabelText(text("trading.field.deliver_to"));
    await waitFor(() => expect(deliverSelect.children.length).toBeGreaterThan(1));
    fireEvent.change(deliverSelect, { target: { value: MANUAL_BRANCH } });

    // Search and add item
    const searchInput = screen.getByLabelText(text("trading.field.find_item"));
    fireEvent.change(searchInput, { target: { value: "RICE" } });
    fireEvent.click(screen.getByRole("button", { name: text("trading.find") }));

    const addButton = await screen.findByRole("button", { name: /Add RICE-01 Samba Rice/i });
    fireEvent.click(addButton);

    // Enter quantity
    const qtyInput = await screen.findByRole("spinbutton", { name: text("trading.column.qty") });
    fireEvent.change(qtyInput, { target: { value: "50" } });

    // Submit order
    const submitBtn = screen.getByRole("button", { name: text("trading.order.submit") }) as HTMLButtonElement;
    await waitFor(() => expect(submitBtn.disabled).toBe(false));
    fireEvent.click(submitBtn);

    await waitFor(() => expect(api.createOrder).toHaveBeenCalledOnce());
    const [req] = api.createOrder.mock.calls[0] as unknown as [{ sellerEntityId: string; deliverToLocationId: string; lines: unknown[] }];
    expect(req.sellerEntityId).toBe(SELLER);
    expect(req.deliverToLocationId).toBe(MANUAL_BRANCH);
    expect(req.lines).toHaveLength(1);

    await waitFor(() => expect(api.submitOrder).toHaveBeenCalledOnce());
  });
});
