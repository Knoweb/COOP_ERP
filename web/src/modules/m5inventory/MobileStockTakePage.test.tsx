import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type {
  StockTakeSessionResponse,
  StockTakeScanResponse,
  StockTakeScanItemResponse,
  StockTakeBatchScanResponse,
  StockTakeReviewResponse,
  StockTakeReconcileResponse
} from "./inventoryApi";
import { MobileStockTakePage } from "./MobileStockTakePage";

const SHOP_ID = "0190f0de-0000-7000-8000-000000000132";
const SESSION_ID = "0190f0de-0000-7000-8000-000000000999";

const mockLocation = {
  locationId: SHOP_ID,
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  locationCode: "SHOP-01",
  locationType: "SHOP" as const,
  nameEn: "Main Cooperative Shop",
  connectivitySpecMet: true,
  status: "ACTIVE" as const
};

const mockSession: StockTakeSessionResponse = {
  sessionId: SESSION_ID,
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  locationId: SHOP_ID,
  scopeKind: "FULL",
  skuIds: [],
  status: "COUNTING",
  expectedLotsCount: 15,
  totalScansCount: 4,
  totalCountedUnits: 25.5
};

const mockScanResult: StockTakeScanResponse = {
  scanId: "0190f0de-0000-7000-8000-000000000501",
  skuId: "0190f6ab-0000-7000-8000-000000000001",
  skuCode: "RICE-SAMBA",
  skuNameEn: "Samba Rice 1kg",
  batchId: "0190f6ab-0000-7000-8000-000000000002",
  batchNo: "B-2026-001",
  condition: "GOOD",
  quantity: 5.0,
  totalCountedForLot: 15.0,
  wasDuplicate: false
};

const mockScanItem: StockTakeScanItemResponse = {
  scanId: "0190f0de-0000-7000-8000-000000000501",
  sessionId: SESSION_ID,
  scannedCode: "479000100010",
  skuId: "0190f6ab-0000-7000-8000-000000000001",
  skuCode: "RICE-SAMBA",
  skuNameEn: "Samba Rice 1kg",
  batchId: "0190f6ab-0000-7000-8000-000000000002",
  condition: "GOOD",
  countedQty: 5.0,
  scannedAt: "2026-11-04T10:00:00Z"
};

const mockReview: StockTakeReviewResponse = {
  sessionId: SESSION_ID,
  locationId: SHOP_ID,
  status: "COUNTING",
  totalExpectedLots: 15,
  totalCountedLots: 12,
  matchedLots: 10,
  discrepantLots: 2,
  uncountedLots: 3,
  totalVarianceQty: -2.0,
  totalVarianceValue: -440.0,
  lines: [
    {
      skuId: "0190f6ab-0000-7000-8000-000000000001",
      skuCode: "RICE-SAMBA",
      skuNameEn: "Samba Rice 1kg",
      condition: "GOOD",
      expectedQty: 20.0,
      countedQty: 18.0,
      varianceQty: -2.0,
      unitCost: 220.0,
      varianceValue: -440.0,
      status: "SHORT"
    }
  ]
};

const mockReconcile: StockTakeReconcileResponse = {
  sessionId: SESSION_ID,
  status: "CLOSED",
  outcome: "POSTED",
  reviewValue: 440.0,
  reviewBand: 1
};

const state = {
  permissions: new Set<string>(["shop.count.record", "inv.stock.view"])
};

const api = {
  locations: vi.fn(async () => [mockLocation]),
  getActiveStockTakeSession: vi.fn(async (): Promise<StockTakeSessionResponse | null> => mockSession),
  getStockTakeSession: vi.fn(async () => mockSession),
  openStockTakeSession: vi.fn(async () => mockSession),
  recordStockTakeScan: vi.fn(async () => mockScanResult),
  listStockTakeScans: vi.fn(async () => [mockScanItem]),
  batchStockTakeScan: vi.fn(async (): Promise<StockTakeBatchScanResponse> => ({
    sessionId: SESSION_ID,
    processedCount: 1,
    duplicateCount: 0,
    totalScansCount: 1
  })),
  reviewStockTakeSession: vi.fn(async () => mockReview),
  reconcileStockTakeSession: vi.fn(async () => mockReconcile)
};

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

vi.mock("./inventoryApi", async () => {
  const actual = await vi.importActual<typeof import("./inventoryApi")>("./inventoryApi");
  return {
    ...actual,
    useInventoryApi: () => api
  };
});

function renderPage(initialEntries = ["/inventory/stock-take"]) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false }
    }
  });

  return render(
    <IntlProvider locale="en" messages={messages.en}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={initialEntries}>
          <Routes>
            <Route path="/inventory/stock-take" element={<MobileStockTakePage />} />
            <Route path="/inventory/stock-take/:sessionId" element={<MobileStockTakePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </IntlProvider>
  );
}

describe("MobileStockTakePage (M3-09)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    api.locations.mockResolvedValue([mockLocation]);
    api.getActiveStockTakeSession.mockResolvedValue(mockSession);
    api.recordStockTakeScan.mockResolvedValue(mockScanResult);
    api.listStockTakeScans.mockResolvedValue([mockScanItem]);
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("allows a user to input a scan and submit a count to the correct session", async () => {
    renderPage();

    // Select the shop location
    await waitFor(() => {
      expect(screen.getByLabelText("Location")).toBeTruthy();
    });
    fireEvent.change(screen.getByLabelText("Location"), {
      target: { value: SHOP_ID }
    });

    // Verify session overview card loads
    await waitFor(() => {
      expect(screen.getByText("Active Session")).toBeTruthy();
      expect(screen.getByText(SESSION_ID)).toBeTruthy();
      expect(screen.getByText("COUNTING")).toBeTruthy();
    });

    // Enter barcode and quantity
    const barcodeInput = screen.getByLabelText("Barcode / SKU");
    const qtyInput = screen.getByLabelText("Quantity");

    fireEvent.change(barcodeInput, { target: { value: "479000100010" } });
    fireEvent.change(qtyInput, { target: { value: "5" } });

    // Submit the scan
    const recordBtn = screen.getByRole("button", { name: "Record Scan" });
    fireEvent.click(recordBtn);

    await waitFor(() => {
      expect(api.recordStockTakeScan).toHaveBeenCalledTimes(1);
    });

    expect(api.recordStockTakeScan).toHaveBeenCalledWith(
      SESSION_ID,
      expect.objectContaining({
        barcode: "479000100010",
        quantity: 5,
        condition: "GOOD"
      }),
      expect.any(String)
    );
  });

  it("handles offline queuing and safe retry without creating duplicate counts", async () => {
    // 1. Simulate network failure on single scan submission
    api.recordStockTakeScan.mockRejectedValueOnce(new TypeError("Failed to fetch"));

    renderPage();

    await waitFor(() => {
      expect(screen.getByLabelText("Location")).toBeTruthy();
    });
    fireEvent.change(screen.getByLabelText("Location"), {
      target: { value: SHOP_ID }
    });

    await waitFor(() => {
      expect(screen.getByText("Active Session")).toBeTruthy();
    });

    // Input scan while offline/network failed
    const barcodeInput = screen.getByLabelText("Barcode / SKU");
    const qtyInput = screen.getByLabelText("Quantity");

    fireEvent.change(barcodeInput, { target: { value: "479000100020" } });
    fireEvent.change(qtyInput, { target: { value: "10" } });

    const recordBtn = screen.getByRole("button", { name: "Record Scan" });
    fireEvent.click(recordBtn);

    // Verify offline queue captured the scan
    await waitFor(() => {
      expect(screen.getByText(/1 scan\(s\) queued offline/i)).toBeTruthy();
    });

    // 2. Perform retry / sync
    api.batchStockTakeScan.mockResolvedValueOnce({
      sessionId: SESSION_ID,
      processedCount: 1,
      duplicateCount: 0,
      totalScansCount: 1
    });

    const syncBtn = screen.getByRole("button", { name: "Sync Queued Scans" });
    fireEvent.click(syncBtn);

    await waitFor(() => {
      expect(api.batchStockTakeScan).toHaveBeenCalledTimes(1);
    });

    expect(api.batchStockTakeScan).toHaveBeenCalledWith(
      SESSION_ID,
      expect.objectContaining({
        scans: [
          expect.objectContaining({
            barcode: "479000100020",
            quantity: 10
          })
        ]
      }),
      expect.any(String)
    );

    await waitFor(() => {
      expect(screen.getByText(/Successfully synced 1 scan\(s\)/i)).toBeTruthy();
    });

    // 3. Simulate subsequent duplicate submission retry (e.g. timeout retry where server already received scan)
    // Server returns duplicateCount: 1 without incrementing total counted units
    api.batchStockTakeScan.mockResolvedValueOnce({
      sessionId: SESSION_ID,
      processedCount: 0,
      duplicateCount: 1,
      totalScansCount: 1
    });

    // Re-queue the same scan to simulate retry
    fireEvent.change(screen.getByLabelText("Barcode / SKU"), { target: { value: "479000100020" } });
    api.recordStockTakeScan.mockResolvedValueOnce({
      ...mockScanResult,
      wasDuplicate: true
    });
    fireEvent.click(screen.getByRole("button", { name: "Record Scan" }));

    await waitFor(() => {
      expect(screen.getByText(/Duplicate scan recognized; no double-counting/i)).toBeTruthy();
    });
  });

  it("offers to start a new stock-taking session when none is active", async () => {
    api.getActiveStockTakeSession.mockResolvedValue(null);

    renderPage();

    await waitFor(() => {
      expect(screen.getByLabelText("Location")).toBeTruthy();
    });
    fireEvent.change(screen.getByLabelText("Location"), {
      target: { value: SHOP_ID }
    });

    await waitFor(() => {
      expect(screen.getByText("No active stock-taking session for this location.")).toBeTruthy();
    });

    const startBtn = screen.getByRole("button", { name: "Start Stock-Taking Session" });
    fireEvent.click(startBtn);

    await waitFor(() => {
      expect(api.openStockTakeSession).toHaveBeenCalledTimes(1);
    });

    expect(api.openStockTakeSession).toHaveBeenCalledWith(
      { locationId: SHOP_ID, scopeKind: "FULL" },
      expect.any(String)
    );
  });

  it("reviews variances and submits reconciliation", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByLabelText("Location")).toBeTruthy();
    });
    fireEvent.change(screen.getByLabelText("Location"), {
      target: { value: SHOP_ID }
    });

    await waitFor(() => {
      expect(screen.getByText("Active Session")).toBeTruthy();
    });

    // Switch to Review tab
    const reviewTab = screen.getByRole("tab", { name: "Review Variances" });
    fireEvent.click(reviewTab);

    await waitFor(() => {
      expect(api.reviewStockTakeSession).toHaveBeenCalledWith(SESSION_ID);
      expect(screen.getByText("Variance Review Report")).toBeTruthy();
      expect(screen.getByText("SHORT")).toBeTruthy();
    });

    // Reconcile with uncounted handling SKIP
    const handlingSelect = screen.getByLabelText("Uncounted Lots Handling");
    fireEvent.change(handlingSelect, { target: { value: "SKIP" } });

    const reasonInput = screen.getByLabelText("Reason for skipping uncounted lots");
    fireEvent.change(reasonInput, { target: { value: "Deferred until morning delivery" } });

    const reconcileBtn = screen.getByRole("button", { name: "Reconcile & Submit Count" });
    fireEvent.click(reconcileBtn);

    await waitFor(() => {
      expect(api.reconcileStockTakeSession).toHaveBeenCalledTimes(1);
    });

    expect(api.reconcileStockTakeSession).toHaveBeenCalledWith(
      SESSION_ID,
      {
        uncountedHandling: "SKIP",
        skipReason: "Deferred until morning delivery"
      },
      expect.any(String)
    );
  });
});

