import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { StockExplanation } from "./inventoryApi";
import { StockExplanationModal } from "./StockExplanationModal";

const LOCATION_ID = "0190f0de-0000-7000-8000-000000000101";
const SKU_ID = "0190f0de-0000-7000-8000-000000000102";

const mockExplanation: StockExplanation = {
  locationId: LOCATION_ID,
  skuId: SKU_ID,
  skuCode: "SKU-SUGAR-1KG",
  skuNameEn: "White Sugar 1kg",
  baseUomCode: "KG",
  departmentNameEn: "Dry Groceries",
  qtyOnHand: 150.0,
  reservedQty: 20.0,
  availableQty: 130.0,
  inTransitIncomingQty: 50.0,
  inTransitOutgoingQty: 10.0,
  countedVarianceQty: -2.0,
  remainingStockQty: 170.0,
  lots: [
    {
      stockLotId: "0190f0de-0000-7000-8000-000000000201",
      locationId: LOCATION_ID,
      skuId: SKU_ID,
      batchId: "0190f0de-0000-7000-8000-000000000211",
      batchNo: "BATCH-2026-A",
      expiryDate: "2027-06-30",
      condition: "GOOD",
      qtyOnHand: 100.0,
      unitCost: 220.0,
      negative: false,
      expired: false,
      fefoRank: 1
    },
    {
      stockLotId: "0190f0de-0000-7000-8000-000000000202",
      locationId: LOCATION_ID,
      skuId: SKU_ID,
      batchId: "0190f0de-0000-7000-8000-000000000212",
      batchNo: "BATCH-2026-B",
      expiryDate: "2027-12-31",
      condition: "GOOD",
      qtyOnHand: 50.0,
      unitCost: 225.0,
      negative: false,
      expired: false,
      fefoRank: 2
    }
  ],
  reservations: [
    {
      pickListId: "0190f0de-0000-7000-8000-000000000301",
      deliveryDocNumber: "DN-2026-0042",
      reservedQty: 20.0,
      pickStatus: "OPEN",
      reservedAt: "2026-11-04T08:00:00Z"
    }
  ],
  incomingTransfers: [
    {
      transferId: "0190f0de-0000-7000-8000-000000000401",
      documentNo: "TR-2026-0010",
      locationName: "Central Warehouse",
      qty: 50.0,
      status: "DISPATCHED",
      dispatchedAt: "2026-11-03T14:00:00Z"
    }
  ],
  outgoingTransfers: [
    {
      transferId: "0190f0de-0000-7000-8000-000000000402",
      documentNo: "TR-2026-0011",
      locationName: "Branch Shop 2",
      qty: 10.0,
      status: "DISPATCHED",
      dispatchedAt: "2026-11-04T09:00:00Z"
    }
  ],
  latestCount: {
    taskId: "0190f0de-0000-7000-8000-000000000501",
    status: "APPROVED",
    outcome: "DISCREPANT",
    expectedQty: 152.0,
    countedQty: 150.0,
    varianceQty: -2.0,
    varianceValue: -440.0,
    submittedAt: "2026-11-02T16:00:00Z"
  }
};

const api = {
  stockExplanation: vi.fn(async () => mockExplanation)
};

vi.mock("./inventoryApi", async () => {
  const actual = await vi.importActual("./inventoryApi");
  return {
    ...actual,
    useInventoryApi: () => api
  };
});

describe("StockExplanationModal", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } }
    });
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  function renderModal(props: {
    isOpen: boolean;
    onClose?: () => void;
    locationId?: string;
    skuId?: string | null;
  }) {
    const onClose = props.onClose ?? vi.fn();
    return {
      onClose,
      ...render(
        <QueryClientProvider client={queryClient}>
          <IntlProvider locale="en" messages={messages.en}>
            <StockExplanationModal
              isOpen={props.isOpen}
              onClose={onClose}
              locationId={props.locationId ?? LOCATION_ID}
              skuId={props.skuId !== undefined ? props.skuId : SKU_ID}
              skuCode="SKU-SUGAR-1KG"
              skuName="White Sugar 1kg"
            />
          </IntlProvider>
        </QueryClientProvider>
      )
    };
  }

  it("does not render when closed", () => {
    renderModal({ isOpen: false });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("renders modal header and KPI summary when open", async () => {
    renderModal({ isOpen: true });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeTruthy();
      expect(screen.getByText("SKU-SUGAR-1KG")).toBeTruthy();
      expect(screen.getByText("White Sugar 1kg")).toBeTruthy();
    });

    // Check KPI summary values
    await waitFor(() => {
      expect(screen.getByText("150")).toBeTruthy(); // on-hand
      expect(screen.getByText("20")).toBeTruthy(); // reserved
      expect(screen.getByText("130")).toBeTruthy(); // available
      expect(screen.getByText("170")).toBeTruthy(); // remaining
    });
  });

  it("allows switching between tabs (Lots, Reservations, Transfers, Count Audit)", async () => {
    renderModal({ isOpen: true });

    await waitFor(() => {
      expect(screen.getByText("BATCH-2026-A")).toBeTruthy();
      expect(screen.getByText("BATCH-2026-B")).toBeTruthy();
    });

    // Switch to reservations tab
    const reservationsTab = screen.getByRole("button", { name: /Reservations/i });
    fireEvent.click(reservationsTab);

    await waitFor(() => {
      expect(screen.getByText("DN-2026-0042")).toBeTruthy();
      expect(screen.getByText("OPEN")).toBeTruthy();
    });

    // Switch to transfers tab
    const transfersTab = screen.getByRole("button", { name: /In-Transit Transfers/i });
    fireEvent.click(transfersTab);

    await waitFor(() => {
      expect(screen.getByText("TR-2026-0010")).toBeTruthy();
      expect(screen.getByText("Central Warehouse")).toBeTruthy();
      expect(screen.getByText("TR-2026-0011")).toBeTruthy();
      expect(screen.getByText("Branch Shop 2")).toBeTruthy();
    });

    // Switch to count audit tab
    const countTab = screen.getByRole("button", { name: /Count Audit/i });
    fireEvent.click(countTab);

    await waitFor(() => {
      expect(screen.getByText("APPROVED")).toBeTruthy();
      expect(screen.getByText("152")).toBeTruthy();
    });
  });

  it("calls onClose when close button is clicked", async () => {
    const { onClose } = renderModal({ isOpen: true });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeTruthy();
    });

    const closeButtons = screen.getAllByRole("button", { name: /Close/i });
    fireEvent.click(closeButtons[0]);

    expect(onClose).toHaveBeenCalled();
  });
});
