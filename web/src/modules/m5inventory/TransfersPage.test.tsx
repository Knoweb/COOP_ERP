import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Transfer, LotBalance, Location } from "./inventoryApi";
import { TransfersPage } from "./TransfersPage";

const WAREHOUSE = "0190f0de-0000-7000-8000-000000000101";
const SHOP = "0190f0de-0000-7000-8000-000000000102";
const TRANSFER_1 = "0190f0de-0000-7000-8000-000000000201";
const TRANSFER_2 = "0190f0de-0000-7000-8000-000000000202";
const TRANSFER_3 = "0190f0de-0000-7000-8000-000000000203";
const TRANSFER_4 = "0190f0de-0000-7000-8000-000000000204";
const SKU_SUGAR = "0190f6ab-0000-7000-8000-000000000001";
const BATCH_1 = "0190f6ac-0000-7000-8000-000000000001";

const location = (locationId: string, locationCode: string, locationType: "WAREHOUSE" | "SHOP"): Location =>
  ({
    locationId,
    ownerEntityId: "e1",
    locationCode,
    locationType,
    nameEn: locationCode,
    status: "ACTIVE"
  }) as unknown as Location;

const mockLocations: Location[] = [
  location(WAREHOUSE, "Main Warehouse", "WAREHOUSE"),
  location(SHOP, "Branch Shop", "SHOP")
];

const mockLots: LotBalance[] = [
  {
    stockLotId: "lot-1",
    locationId: WAREHOUSE,
    skuId: SKU_SUGAR,
    batchId: BATCH_1,
    batchNo: "BATCH-001",
    condition: "GOOD",
    qtyOnHand: 100,
    negative: false,
    unitCost: 50
  }
];

const state = {
  permissions: new Set<string>(["inv.transfer.issue", "shop.transfer.receive"]),
  transfers: [] as Transfer[]
};

function createTransfer(
  transferId: string,
  fromLocationId: string,
  toLocationId: string,
  status: Transfer["status"],
  docNo: string
): Transfer {
  return {
    transferId,
    fromLocationId,
    toLocationId,
    status,
    documentNo: docNo,
    issuedBy: "u1",
    issuedAt: "2026-10-30T08:00:00Z",
    dispatchedBy: status !== "ISSUED" ? "u1" : undefined,
    dispatchedAt: status !== "ISSUED" ? "2026-10-30T09:00:00Z" : undefined,
    receivedBy: status === "RECEIVED" ? "u2" : undefined,
    receivedAt: status === "RECEIVED" ? "2026-10-30T10:00:00Z" : undefined,
    lines: [{ transferLineId: "line-1", lineNo: 1, skuId: SKU_SUGAR, batchId: BATCH_1, qty: 10 }]
  };
}

const api = {
  locations: vi.fn(async () => mockLocations),
  transfers: vi.fn(async (_locId: string) => state.transfers),
  balances: vi.fn(async (_locId: string) => mockLots),
  issueTransfer: vi.fn(async () =>
    createTransfer(TRANSFER_1, WAREHOUSE, SHOP, "ISSUED", "XFR-001")
  ),
  dispatchTransfer: vi.fn(async (transferId: string) =>
    createTransfer(transferId, WAREHOUSE, SHOP, "IN_TRANSIT", "XFR-001")
  ),
  receiveTransfer: vi.fn(async (transferId: string) =>
    createTransfer(transferId, WAREHOUSE, SHOP, "RECEIVED", "XFR-001")
  ),
  cancelTransfer: vi.fn(async (transferId: string) =>
    createTransfer(transferId, WAREHOUSE, SHOP, "CANCELLED", "XFR-001")
  )
};

vi.mock("./inventoryApi", () => ({
  useInventoryApi: () => api
}));

vi.mock("./SkuLabel", () => ({
  SkuLabel: ({ skuId }: { skuId: string }) => <span data-testid="sku-label">{skuId}</span>
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <TransfersPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("TransfersPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["inv.transfer.issue", "shop.transfer.receive"]);
    state.transfers = [
      createTransfer(TRANSFER_1, WAREHOUSE, SHOP, "ISSUED", "XFR-001"),
      createTransfer(TRANSFER_2, WAREHOUSE, SHOP, "IN_TRANSIT", "XFR-002"),
      createTransfer(TRANSFER_3, WAREHOUSE, SHOP, "RECEIVED", "XFR-003"),
      createTransfer(TRANSFER_4, WAREHOUSE, SHOP, "CANCELLED", "XFR-004")
    ];
  });

  afterEach(cleanup);

  it("renders transfers and displays accurate status labels (not Draft or Void)", async () => {
    renderPage();

    // Verify status labels exist
    expect(await screen.findByText("Issued", {}, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByText("In transit")).toBeTruthy();
    expect(screen.getByText("Received")).toBeTruthy();
    expect(screen.getByText("Cancelled")).toBeTruthy();

    // Verify document numbers
    expect(screen.getByText(/XFR-001/)).toBeTruthy();
    expect(screen.getByText(/XFR-002/)).toBeTruthy();
  }, 15000);

  it("allows source store issuer to dispatch an ISSUED transfer", async () => {
    renderPage();

    const dispatchButton = await screen.findByRole(
      "button",
      { name: text("inventory.transfer.dispatch") },
      { timeout: 10000 }
    );
    expect(dispatchButton).toBeTruthy();

    fireEvent.click(dispatchButton);

    await waitFor(() => {
      expect(api.dispatchTransfer).toHaveBeenCalledWith(TRANSFER_1, expect.any(String));
    });
  }, 15000);

  it("allows destination shop receiver to receive an IN_TRANSIT transfer", async () => {
    renderPage();

    // Wait for page to initialize at WAREHOUSE
    await screen.findByText("Issued", {}, { timeout: 10000 });

    // Switch location to SHOP
    const locationSelect = screen.getByRole("combobox", { name: text("inventory.field.location") });
    fireEvent.change(locationSelect, { target: { value: SHOP } });

    // For SHOP (destination), TRANSFER_2 is IN_TRANSIT and should have Receive button
    const receiveButton = await screen.findByRole(
      "button",
      { name: text("inventory.transfer.receive") },
      { timeout: 10000 }
    );
    expect(receiveButton).toBeTruthy();

    fireEvent.click(receiveButton);

    await waitFor(() => {
      expect(api.receiveTransfer).toHaveBeenCalledWith(TRANSFER_2, expect.any(String));
    });
  }, 15000);

  it("allows source store issuer to cancel an unreceived transfer with reason", async () => {
    renderPage();

    // Find cancel button on WAREHOUSE transfers
    const cancelButtons = await screen.findAllByRole(
      "button",
      { name: text("inventory.transfer.cancel") },
      { timeout: 10000 }
    );
    expect(cancelButtons.length).toBeGreaterThan(0);

    // Click cancel on first transfer (TRANSFER_1, ISSUED)
    fireEvent.click(cancelButtons[0]);

    // Reason input appears
    const reasonInput = screen.getByPlaceholderText(text("inventory.transfer.cancel_reason"));
    const confirmButton = screen.getByRole("button", { name: text("inventory.transfer.cancel_confirm") });

    // Confirm is initially disabled because reason is empty
    expect((confirmButton as HTMLButtonElement).disabled).toBe(true);

    // Type reason
    fireEvent.change(reasonInput, { target: { value: "Wrong item sent" } });
    expect((confirmButton as HTMLButtonElement).disabled).toBe(false);

    // Confirm cancellation
    fireEvent.click(confirmButton);

    await waitFor(() => {
      expect(api.cancelTransfer).toHaveBeenCalledWith(
        TRANSFER_1,
        { reason: "Wrong item sent", physicalReturnVerified: undefined },
        expect.any(String)
      );
    });
  }, 15000);

  it("requires physical return verification when cancelling an IN_TRANSIT transfer", async () => {
    renderPage();

    // Find cancel buttons (TRANSFER_1 is ISSUED, TRANSFER_2 is IN_TRANSIT)
    const cancelButtons = await screen.findAllByRole(
      "button",
      { name: text("inventory.transfer.cancel") },
      { timeout: 10000 }
    );

    // Click cancel on TRANSFER_2 (IN_TRANSIT)
    fireEvent.click(cancelButtons[1]);

    const reasonInput = screen.getByPlaceholderText(text("inventory.transfer.cancel_reason"));
    const checkbox = screen.getByLabelText(text("inventory.transfer.physical_return_verified"));
    const confirmButton = screen.getByRole("button", { name: text("inventory.transfer.cancel_confirm") });

    // Type reason without checking physical return verification
    fireEvent.change(reasonInput, { target: { value: "Goods returned by driver" } });
    expect((confirmButton as HTMLButtonElement).disabled).toBe(true);

    // Check physical return verified
    fireEvent.click(checkbox);
    expect((confirmButton as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(confirmButton);

    await waitFor(() => {
      expect(api.cancelTransfer).toHaveBeenCalledWith(
        TRANSFER_2,
        { reason: "Goods returned by driver", physicalReturnVerified: true },
        expect.any(String)
      );
    });
  }, 15000);

  it("filters transfers by Outbound and Inbound views", async () => {
    renderPage();

    await screen.findByText(/XFR-001/, {}, { timeout: 10000 });

    const outboundButton = screen.getByRole("button", { name: text("inventory.transfer.outbound") });
    const inboundButton = screen.getByRole("button", { name: text("inventory.transfer.inbound") });

    // Click Outbound (from WAREHOUSE) -> transfers sent from WAREHOUSE remain visible
    fireEvent.click(outboundButton);
    expect(screen.getByText(/XFR-001/)).toBeTruthy();

    // Click Inbound (to WAREHOUSE) -> none arrive at WAREHOUSE in our mock
    fireEvent.click(inboundButton);
    expect(screen.getByText(text("inventory.transfer.none"))).toBeTruthy();
  }, 15000);

  it("hides Dispatch and Cancel buttons when user lacks inv.transfer.issue permission", async () => {
    state.permissions = new Set(["shop.transfer.receive"]);
    renderPage();

    await screen.findByText("Issued", {}, { timeout: 10000 });

    expect(screen.queryByRole("button", { name: text("inventory.transfer.dispatch") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("inventory.transfer.cancel") })).toBeNull();
  }, 15000);

  it("hides Receive button when user lacks shop.transfer.receive permission", async () => {
    state.permissions = new Set(["inv.transfer.issue"]);
    renderPage();

    // Switch to SHOP
    const locationSelect = await screen.findByRole(
      "combobox",
      { name: text("inventory.field.location") },
      { timeout: 10000 }
    );
    fireEvent.change(locationSelect, { target: { value: SHOP } });

    await screen.findByText("In transit", {}, { timeout: 10000 });
    expect(screen.queryByRole("button", { name: text("inventory.transfer.receive") })).toBeNull();
  }, 15000);

  it("allows issuing a new transfer with destination and quantities", async () => {
    renderPage();

    await screen.findByText("Issued", {}, { timeout: 10000 });

    const destSelect = await screen.findByLabelText(text("inventory.transfer.destination"), {}, { timeout: 10000 });
    fireEvent.change(destSelect, { target: { value: SHOP } });

    const qtyInput = await screen.findByLabelText(text("inventory.transfer.qty_to_send"), {}, { timeout: 10000 });
    fireEvent.change(qtyInput, { target: { value: "25" } });

    const sendButton = screen.getByRole("button", { name: text("inventory.transfer.issue") });
    await waitFor(() => expect((sendButton as HTMLButtonElement).disabled).toBe(false));

    fireEvent.click(sendButton);

    await waitFor(() => {
      expect(api.issueTransfer).toHaveBeenCalledWith(
        {
          fromLocationId: WAREHOUSE,
          toLocationId: SHOP,
          lines: [{ batchId: BATCH_1, qty: 25 }]
        },
        expect.any(String)
      );
    });
  }, 15000);
});
