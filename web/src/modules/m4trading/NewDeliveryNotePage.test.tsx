import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { NewDeliveryNotePage } from "./NewDeliveryNotePage";

const ORDER_ID = "0190f4cc-0000-7000-8000-000000000008";
const BUYER_ID = "0190f0de-0000-7000-8000-0000000000e1";
const SELLER_ID = "0190f000-0000-7000-8000-000000000001";
const DELIVER_TO = "0190f0de-0000-7000-8000-000000000111";
const SKU_ID = "0190f4bb-0000-7000-8000-000000000001";

const api = {
  order: vi.fn(async () => ({
    orderId: ORDER_ID,
    docNumber: "D102-ORD-0000008",
    status: "ACCEPTED",
    buyerEntityId: BUYER_ID,
    sellerEntityId: SELLER_ID,
    deliverToLocationId: DELIVER_TO,
    deliverTo: { code: "W01", nameEn: "Jaffna warehouse" },
    lines: [{ lineId: "line-1", skuId: SKU_ID, uomCode: "EA", orderedQty: 10, allocatedQty: 10, fulfilledQty: 0 }]
  })),
  locations: vi.fn(async () => [
    { locationId: "fw01", locationCode: "FW01", nameEn: "Federation central warehouse, Peliyagoda" },
    { locationId: "fw02", locationCode: "FW02", nameEn: "Kurunegala regional warehouse" }
  ]),
  balances: vi.fn(async () => [
    {
      stockLotId: "lot-1",
      batchId: "batch-1",
      skuId: SKU_ID,
      condition: "GOOD",
      qtyOnHand: 400,
      fefoRank: 1,
      batchNo: "DEMO-2026-16",
      expiryDate: "2027-08-31"
    }
  ]),
  createDeliveryNote: vi.fn(),
  entity: vi.fn(async () => ({ entityId: BUYER_ID, entityCode: "D102", nameEn: "Northern Cooperative Distributors" })),
  sku: vi.fn(async () => ({ skuId: SKU_ID, skuCode: "SKU-27GD6X83", nameEn: "Coconut oil 500 ml" }))
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));

const text = (id: string) => (messages.en as Record<string, string>)[id];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/trading/delivery-notes/new?orderId=${ORDER_ID}`]}>
          <Routes>
            <Route path="/trading/delivery-notes/new" element={<NewDeliveryNotePage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("NewDeliveryNotePage dropdowns", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("renders custom accessible dropdowns for warehouse dispatch selection and table batches", async () => {
    renderPage();

    const warehouseCombobox = await screen.findByRole("combobox", { name: text("trading.field.from_location") });
    expect(warehouseCombobox.className).toContain("visually-hidden");

    const warehouseTrigger = screen.getByRole("button", { name: text("trading.field.choose") });
    fireEvent.click(warehouseTrigger);

    const listbox = screen.getByRole("listbox");
    const warehouseOption = within(listbox).getByRole("option", { name: "FW01 Federation central warehouse, Peliyagoda" });
    fireEvent.click(warehouseOption);

    expect(screen.getByRole("button", { name: "FW01 Federation central warehouse, Peliyagoda" })).toBeTruthy();

    const batchTrigger = await screen.findByRole("button", { name: /DEMO-2026-16/ });
    expect(batchTrigger.closest(".ui-select--compact")).toBeTruthy();

    fireEvent.click(batchTrigger);
    const batchListbox = screen.getByRole("listbox");
    expect(batchListbox.className).toContain("ui-select__menu--portal");
    expect(within(batchListbox).getByRole("option", { name: /DEMO-2026-16/ }).className).toContain("ui-select__item--selected");
  });
});

