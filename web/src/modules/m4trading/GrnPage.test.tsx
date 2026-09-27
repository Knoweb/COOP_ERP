import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { DeliveryNote, Grn, Order } from "./tradingApi";
import { GrnPage } from "./GrnPage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const W01 = "0190f0de-0000-7000-8000-000000000111";
const GRN_ID = "0190f4ee-0000-7000-8000-000000000001";
const NOTE_ID = "0190f4ef-0000-7000-8000-000000000001";
const DROP_ID = "0190f4f0-0000-7000-8000-000000000001";
const ORDER_ID = "0190f4cc-0000-7000-8000-000000000001";

const state = { entityId: BUYER, permissions: new Set<string>() };

const grn: Grn = {
  grnId: GRN_ID,
  docNumber: "D101-GRN-0000001",
  status: "CONFIRMED",
  receiverEntityId: BUYER,
  receiverLocationId: W01,
  sellerEntityId: SELLER,
  dropId: DROP_ID,
  deliveryNoteId: NOTE_ID,
  lines: []
};
const note = {
  deliveryNoteId: NOTE_ID,
  drops: [{ dropId: DROP_ID, seq: 1, shipToLocationId: W01, billToEntityId: BUYER, orderIds: [ORDER_ID], status: "RECEIVED", lines: [] }]
} as unknown as DeliveryNote;
const order = {
  orderId: ORDER_ID,
  deliverToLocationId: W01,
  deliverTo: { code: "W01", nameEn: "Kurunegala warehouse" }
} as unknown as Order;

const api = {
  grn: vi.fn(async () => grn),
  deliveryNote: vi.fn(async () => note),
  order: vi.fn(async () => order),
  receipt: vi.fn(async () => []),
  sku: vi.fn(async () => null),
  entity: vi.fn(async () => null),
  location: vi.fn(async () => null)
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));

function renderGrn() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/trading/grns/${GRN_ID}`]}>
          <Routes>
            <Route path="/trading/grns/:grnId" element={<GrnPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("the GRN card", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("does not ask for the stock moved when the reader may not receive stock (403 before)", async () => {
    Object.assign(state, { entityId: BUYER, permissions: new Set<string>() });
    renderGrn();

    await screen.findByText("D101-GRN-0000001");
    expect(api.receipt).not.toHaveBeenCalled();
  });

  it("asks for the stock moved when the receiver may receive stock", async () => {
    Object.assign(state, { entityId: BUYER, permissions: new Set(["inv.stock.receive"]) });
    renderGrn();

    await waitFor(() => expect(api.receipt).toHaveBeenCalledWith(GRN_ID));
  });

  it("names the buyer's warehouse to the seller from the order the drop delivered, without reading it (404 before)", async () => {
    Object.assign(state, { entityId: SELLER, permissions: new Set<string>() });
    renderGrn();

    expect(await screen.findByText("W01 Kurunegala warehouse")).toBeTruthy();
    expect(api.location).not.toHaveBeenCalled();
    expect(api.receipt).not.toHaveBeenCalled();
  });
});
