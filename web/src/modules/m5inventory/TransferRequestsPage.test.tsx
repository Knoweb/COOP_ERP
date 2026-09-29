import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { TransferRequest } from "./inventoryApi";
import { TransferRequestsPage } from "./TransferRequestsPage";

const STORES = "0190f0de-0000-7000-8000-000000000131";
const SHOP = "0190f0de-0000-7000-8000-000000000132";
const REQUEST = "0190f6aa-0000-7000-8000-000000000001";
const SUGAR = "0190f6ab-0000-7000-8000-000000000001";

const state = { permissions: new Set<string>(["mpcs.transfer.approve"]), status: "REQUESTED" as TransferRequest["status"] };

function request(): TransferRequest {
  return {
    requestId: REQUEST,
    toLocationId: SHOP,
    status: state.status,
    reason: "Weekend",
    requestedAt: "2026-09-29T02:45:00Z",
    lines: [{ lineId: "l1", skuId: SUGAR, qty: 6 }]
  };
}

const location = (locationId: string, locationCode: string, locationType: "WAREHOUSE" | "SHOP") => ({
  locationId,
  ownerEntityId: "e",
  locationCode,
  locationType,
  nameEn: locationCode,
  status: "ACTIVE"
});

const api = {
  transferRequests: vi.fn(async () => [request()]),
  locations: vi.fn(async () => [location(STORES, "STORES", "WAREHOUSE"), location(SHOP, "TOWN", "SHOP")]),
  balances: vi.fn(async () => [{ skuId: SUGAR, condition: "GOOD", qtyOnHand: 4 }]),
  requestTransfer: vi.fn(async () => request()),
  approveTransferRequest: vi.fn(async () => {
    state.status = "APPROVED";
    return request();
  }),
  rejectTransferRequest: vi.fn(async () => request()),
  sku: vi.fn(async () => null)
};

vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));
vi.mock("./SkuLabel", () => ({ SkuLabel: ({ skuId }: { skuId: string }) => <span>{skuId}</span> }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <TransferRequestsPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("transfer requests", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.status = "REQUESTED";
  });
  afterEach(cleanup);

  it("lets the society approve a request from its only warehouse", async () => {
    state.permissions = new Set(["mpcs.transfer.approve"]);
    renderPage();

    const source = (await screen.findByRole("combobox", { name: text("inventory.request.source") }, { timeout: 10000 })) as HTMLSelectElement;
    await waitFor(() => expect(source.value).toBe(STORES));
    fireEvent.click(screen.getByRole("button", { name: text("inventory.request.approve") }));

    await waitFor(() => expect(api.approveTransferRequest).toHaveBeenCalledWith(REQUEST, STORES, expect.any(String)));
    expect(screen.queryByRole("textbox", { name: text("inventory.request.qty") })).toBeNull();
  }, 15000);

  it("lets the shop ask for more of what it sells, leaving the source to the society", async () => {
    state.permissions = new Set(["shop.transfer.request"]);
    api.locations.mockResolvedValueOnce([location(SHOP, "TOWN", "SHOP")]);
    renderPage();

    const qty = await screen.findByRole("textbox", { name: text("inventory.request.qty") }, { timeout: 10000 });
    fireEvent.change(qty, { target: { value: "6" } });
    fireEvent.click(screen.getByRole("button", { name: text("inventory.request.send") }));

    await waitFor(() =>
      expect(api.requestTransfer).toHaveBeenCalledWith(
        { toLocationId: SHOP, reason: undefined, lines: [{ skuId: SUGAR, qty: 6 }] },
        expect.any(String)
      )
    );
    expect(screen.queryByRole("button", { name: text("inventory.request.approve") })).toBeNull();
  }, 15000);
});
