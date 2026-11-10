import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { LotBalance, StockSummary, DepartmentCategory, Uom } from "./inventoryApi";
import { StockPage } from "./StockPage";

const LOCATION_ID = "0190f0de-0000-7000-8000-000000000101";
const SKU_1 = "0190f0de-0000-7000-8000-000000000102";
const SKU_2 = "0190f0de-0000-7000-8000-000000000103";

const mockSummaries: StockSummary[] = [
  {
    locationId: LOCATION_ID,
    skuId: SKU_1,
    skuCode: "SKU-RICE-5KG",
    skuNameEn: "White Rice 5kg",
    baseUomCode: "BAG",
    departmentNameEn: "Staples",
    qtyOnHand: 40.0,
    reservedQty: 5.0,
    availableQty: 35.0,
    inTransitIncomingQty: 20.0,
    inTransitOutgoingQty: 0.0,
    countedVarianceQty: 0.0,
    latestCountStatus: "MATCHED",
    remainingStockQty: 60.0,
    lotCount: 1
  },
  {
    locationId: LOCATION_ID,
    skuId: SKU_2,
    skuCode: "SKU-SUGAR-1KG",
    skuNameEn: "White Sugar 1kg",
    baseUomCode: "KG",
    departmentNameEn: "Dry Groceries",
    qtyOnHand: 0.0,
    reservedQty: 0.0,
    availableQty: 0.0,
    inTransitIncomingQty: 10.0,
    inTransitOutgoingQty: 0.0,
    countedVarianceQty: -5.0,
    latestCountStatus: "DISCREPANT",
    remainingStockQty: 10.0,
    lotCount: 0
  }
];

const mockBalances: LotBalance[] = [
  {
    stockLotId: "0190f0de-0000-7000-8000-000000000201",
    locationId: LOCATION_ID,
    skuId: SKU_1,
    batchId: "0190f0de-0000-7000-8000-000000000211",
    batchNo: "BAT-RICE-01",
    expiryDate: "2027-12-31",
    condition: "GOOD",
    qtyOnHand: 40.0,
    unitCost: 1200.0,
    negative: false,
    expired: false,
    fefoRank: 1
  }
];

const mockDepartments: DepartmentCategory[] = [
  {
    departmentId: "0190f0de-0000-7000-8000-000000000051",
    code: "STAPLES",
    nameEn: "Staples",
    displayOrder: 1,
    status: "ACTIVE"
  }
];

const mockUoms: Uom[] = [
  {
    uomCode: "BAG",
    nameEn: "Bag",
    weight: false
  },
  {
    uomCode: "KG",
    nameEn: "Kilogram",
    weight: true
  }
];

const api = {
  departments: vi.fn(async () => mockDepartments),
  uoms: vi.fn(async () => mockUoms),
  stockSummaries: vi.fn(async () => mockSummaries),
  balances: vi.fn(async () => mockBalances),
  stockExplanation: vi.fn(async () => null)
};

vi.mock("./inventoryApi", async () => {
  const actual = await vi.importActual("./inventoryApi");
  return {
    ...actual,
    useInventoryApi: () => api
  };
});

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: () => true
}));

// Mock LocationPicker to directly trigger location selection
vi.mock("./LocationPicker", () => ({
  LocationPicker: ({ value, onChange }: { value: string; onChange: (id: string) => void }) => (
    <div data-testid="location-picker">
      <button
        type="button"
        data-testid="select-location-btn"
        onClick={() => onChange(LOCATION_ID)}
      >
        Select Location
      </button>
      <span data-testid="current-location">{value}</span>
    </div>
  )
}));

describe("StockPage", () => {
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

  function renderPage() {
    return render(
      <QueryClientProvider client={queryClient}>
        <IntlProvider locale="en" messages={messages.en}>
          <MemoryRouter>
            <StockPage />
          </MemoryRouter>
        </IntlProvider>
      </QueryClientProvider>
    );
  }

  it("renders location prompt when no location is selected", () => {
    renderPage();
    expect(
      screen.getByText("Select a location to view stock position")
    ).toBeTruthy();
  });

  it("links stock users to reorder controls", () => {
    renderPage();

    expect(screen.getByRole("link", { name: "Reorder controls" }).getAttribute("href")).toBe(
      "/inventory/reorder-controls"
    );
  });

  it("links stock viewers to expiry and slow-moving stock", () => {
    renderPage();

    expect(
      screen.getByRole("link", { name: "Expiry & Slow-Moving Stock" }).getAttribute("href")
    ).toBe("/inventory/expiry");
  });

  it("renders KPI cards and stock summary rows once location is chosen", async () => {
    renderPage();

    const selectBtn = screen.getByTestId("select-location-btn");
    fireEvent.click(selectBtn);

    await waitFor(() => {
      // 2 total items
      expect(screen.getByText("2")).toBeTruthy();
      // On hand 40 (40 + 0) appears in KPI card and row
      expect(screen.getAllByText("40").length).toBeGreaterThanOrEqual(1);
      // Discrepancies count: 1 (SKU_2)
      expect(screen.getByText("1")).toBeTruthy();
    });

    await waitFor(() => {
      expect(screen.getByText("SKU-RICE-5KG")).toBeTruthy();
      expect(screen.getByText("SKU-SUGAR-1KG")).toBeTruthy();
      expect(screen.getAllByText("Staples").length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText("Dry Groceries")).toBeTruthy();
    });
  });

  it("filters items by quick status filter pill (Out of Stock)", async () => {
    renderPage();

    const selectBtn = screen.getByTestId("select-location-btn");
    fireEvent.click(selectBtn);

    await waitFor(() => {
      expect(screen.getByText("SKU-RICE-5KG")).toBeTruthy();
    });

    const outOfStockPill = screen.getByRole("radio", { name: "Out of Stock" });
    fireEvent.click(outOfStockPill);

    await waitFor(() => {
      expect(screen.queryByText("SKU-RICE-5KG")).toBeNull();
      expect(screen.getByText("SKU-SUGAR-1KG")).toBeTruthy();
    });
  });

  it("expands an SKU row to display lot breakdown", async () => {
    renderPage();

    const selectBtn = screen.getByTestId("select-location-btn");
    fireEvent.click(selectBtn);

    await waitFor(() => {
      expect(screen.getByText("SKU-RICE-5KG")).toBeTruthy();
    });

    const expandButtons = screen.getAllByRole("button", { name: /Show Lots/i });
    fireEvent.click(expandButtons[0]);

    await waitFor(() => {
      expect(screen.getByText("BAT-RICE-01")).toBeTruthy();
      expect(screen.getByText("Reprice")).toBeTruthy();
    });
  });

  it("opens explanation modal when clicking Explain button", async () => {
    renderPage();

    const selectBtn = screen.getByTestId("select-location-btn");
    fireEvent.click(selectBtn);

    await waitFor(() => {
      expect(screen.getByText("SKU-RICE-5KG")).toBeTruthy();
    });

    const explainButtons = screen.getAllByRole("button", { name: "Explain" });
    fireEvent.click(explainButtons[0]);

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeTruthy();
      expect(
        screen.getByText("Stock Quantity Breakdown & Sources")
      ).toBeTruthy();
    });
  });

  it("does not incorrectly combine quantities from different UOMs in KPI totals", async () => {
    renderPage();

    const selectBtn = screen.getByTestId("select-location-btn");
    fireEvent.click(selectBtn);

    await waitFor(() => {
      // Mixed UOMs indicator is displayed because mockSummaries has BAG and KG
      expect(screen.getByText("Mixed UOMs")).toBeTruthy();
      // On-Hand and other KPI cards display SKU counts instead of summing mixed units
      expect(screen.getAllByText("1 SKUs").length).toBeGreaterThanOrEqual(1);
    });
  });
});
