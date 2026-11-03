import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Location, Sku, RevenueEstimateResponse } from "./reportingApi";
import { RevenueEstimatorPage } from "./RevenueEstimatorPage";
import { ApiProblem } from "../../shell/api/client";

const mockLocations: Location[] = [
  {
    locationId: "0190f8bb-0000-7000-8000-000000000001",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    locationCode: "LOC-01",
    locationType: "SHOP",
    nameEn: "Polgolla Main Shop",
    connectivitySpecMet: true,
    status: "ACTIVE"
  }
];

const mockSkus: Sku[] = [
  {
    skuId: "0190f8aa-0000-7000-8000-000000000001",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    skuCode: "SKU-RICE",
    nameEn: "Samba Rice 5kg",
    baseUomCode: "BAG",
    taxCategoryId: "0190f8dd-0000-7000-8000-000000000001",
    multiMrpPolicy: "ANY",
    originKind: "LOCAL",
    soldByWeight: false,
    batchTracked: false,
    expiryTracked: false,
    hasPrintedMrp: false,
    status: "SHARED"
  },
  {
    skuId: "0190f8aa-0000-7000-8000-000000000002",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    skuCode: "SKU-SUGAR",
    nameEn: "White Sugar 1kg",
    baseUomCode: "KG",
    taxCategoryId: "0190f8dd-0000-7000-8000-000000000001",
    multiMrpPolicy: "ANY",
    originKind: "LOCAL",
    soldByWeight: true,
    batchTracked: false,
    expiryTracked: false,
    hasPrintedMrp: false,
    status: "SHARED"
  }
];

const mockEstimateResponse: RevenueEstimateResponse = {
  targetRevenue: "100000.00",
  totalEstimatedRevenue: "99990.00",
  targetVariance: "-10.00",
  totalMixPercent: "100.00",
  currency: "LKR",
  locationId: "0190f8bb-0000-7000-8000-000000000001",
  priceType: "RETAIL",
  businessDate: "2026-10-29",
  assumptions: {
    targetRevenue: "100000.00",
    locationId: "0190f8bb-0000-7000-8000-000000000001",
    priceType: "RETAIL",
    businessDate: "2026-10-29",
    roundingMethod: "INTEGER_PIECE_OR_THREE_DECIMAL_WEIGHTED",
    priceResolutionPolicy: "LIST_OR_COMMERCIAL_BOUNDED_BY_MRP_AND_CONTROL"
  },
  items: [
    {
      skuId: "0190f8aa-0000-7000-8000-000000000001",
      skuCode: "SKU-RICE",
      skuName: "Samba Rice 5kg",
      uomCode: "BAG",
      unitPrice: "1200.00",
      priceSource: "PRICE_LIST",
      mixPercent: "60.00",
      targetShare: "60000.00",
      rawQuantity: "50.000",
      roundedQuantity: "50",
      estimatedRevenue: "60000.00",
      roundingVariance: "0.00"
    },
    {
      skuId: "0190f8aa-0000-7000-8000-000000000002",
      skuCode: "SKU-SUGAR",
      skuName: "White Sugar 1kg",
      uomCode: "KG",
      unitPrice: "245.00",
      priceSource: "PRICE_LIST",
      mixPercent: "40.00",
      targetShare: "40000.00",
      rawQuantity: "163.265",
      roundedQuantity: "163.224",
      estimatedRevenue: "39990.00",
      roundingVariance: "-10.00"
    }
  ]
};

const api = {
  locations: vi.fn(async () => mockLocations),
  searchSkus: vi.fn(async () => mockSkus),
  estimateRevenue: vi.fn(async () => mockEstimateResponse)
};

vi.mock("./reportingApi", async () => {
  const actual = await vi.importActual<object>("./reportingApi");
  return {
    ...actual,
    useReportingApi: () => api
  };
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/reporting/revenue-estimator"]}>
          <Routes>
            <Route path="/reporting/revenue-estimator" element={<RevenueEstimatorPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("RevenueEstimatorPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.locations.mockResolvedValue(mockLocations);
    api.searchSkus.mockResolvedValue(mockSkus);
    api.estimateRevenue.mockResolvedValue(mockEstimateResponse);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders the estimator page with initial inputs and empty mix state", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Revenue Estimation & Sales Mix Planner")).toBeDefined();
      expect(screen.getByText("Target Revenue (LKR)")).toBeDefined();
      expect(screen.getByText("Shop / Location")).toBeDefined();
      expect(screen.getByText("Pricing Basis")).toBeDefined();
      expect(screen.getByText("Product Mix Planning")).toBeDefined();
      expect(screen.getByText("No SKUs added yet. Search and add SKUs to the planning mix.")).toBeDefined();
    });

    const calcBtn = screen.getByRole("button", { name: "Calculate Required Quantities" });
    expect((calcBtn as HTMLButtonElement).disabled).toBe(true);
  });

  it("adds SKUs, adjusts percentages and shows validation warning when not 100%", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("LOC-01 Polgolla Main Shop")).toBeDefined();
    });

    // Select location
    fireEvent.change(screen.getByLabelText("Shop / Location"), {
      target: { value: "0190f8bb-0000-7000-8000-000000000001" }
    });

    // Add first SKU
    const skuSelect = screen.getByLabelText("Select Item / SKU", { selector: "select" });
    fireEvent.change(skuSelect, {
      target: { value: "0190f8aa-0000-7000-8000-000000000001" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Add SKU" }));

    // Rice is added with 100%
    await waitFor(() => {
      expect(screen.getByText("SKU-RICE")).toBeDefined();
      expect(screen.getByText("Total Mix: 100.00%")).toBeDefined();
    });

    // Change Rice to 60%
    const riceMixInput = screen.getByDisplayValue("100");
    fireEvent.change(riceMixInput, { target: { value: "60" } });

    // Warning appears that mix != 100%
    await waitFor(() => {
      expect(screen.getByText("Total Mix: 60.00%")).toBeDefined();
      expect(screen.getByText(/Product mix percentages must sum exactly to 100.00%/i)).toBeDefined();
    });

    // Calculate button should still be disabled
    const calcBtn = screen.getByRole("button", { name: "Calculate Required Quantities" });
    expect((calcBtn as HTMLButtonElement).disabled).toBe(true);

    // Add second SKU (Sugar)
    fireEvent.change(skuSelect, {
      target: { value: "0190f8aa-0000-7000-8000-000000000002" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Add SKU" }));

    // Sugar auto-gets remaining 40%
    await waitFor(() => {
      expect(screen.getByText("SKU-SUGAR")).toBeDefined();
      expect(screen.getByText("Total Mix: 100.00%")).toBeDefined();
    });

    // Calculate button is now enabled
    expect((calcBtn as HTMLButtonElement).disabled).toBe(false);
  });

  it("calculates revenue estimates and renders summary KPIs and detailed table", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("LOC-01 Polgolla Main Shop")).toBeDefined();
    });

    // Select location
    fireEvent.change(screen.getByLabelText("Shop / Location"), {
      target: { value: "0190f8bb-0000-7000-8000-000000000001" }
    });

    // Add SKU
    const skuSelect = screen.getByLabelText("Select Item / SKU", { selector: "select" });
    fireEvent.change(skuSelect, {
      target: { value: "0190f8aa-0000-7000-8000-000000000001" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Add SKU" }));

    await waitFor(() => {
      expect(screen.getByText("Total Mix: 100.00%")).toBeDefined();
    });

    // Click calculate
    const calcBtn = screen.getByRole("button", { name: "Calculate Required Quantities" });
    fireEvent.click(calcBtn);

    await waitFor(() => {
      expect(api.estimateRevenue).toHaveBeenCalledTimes(1);
      expect(screen.getByText("Estimation Results")).toBeDefined();
      expect(screen.getByText("Total Estimated Revenue")).toBeDefined();
      expect(screen.getByText("Target Variance")).toBeDefined();
      expect(screen.getByText("Variance Breakdown:")).toBeDefined();
      expect(screen.getByText("Planning Assumptions:")).toBeDefined();
    });
  });

  it("handles unresolvable price error gracefully with warning banner", async () => {
    api.estimateRevenue.mockRejectedValueOnce(
      new ApiProblem({
        status: 422,
        code: "m8.estimator.unresolved_price",
        title: "Unresolved Price",
        params: { sku: "SKU-NOPRICE" }
      })
    );

    renderPage();

    await waitFor(() => {
      expect(screen.getByText("LOC-01 Polgolla Main Shop")).toBeDefined();
    });

    fireEvent.change(screen.getByLabelText("Shop / Location"), {
      target: { value: "0190f8bb-0000-7000-8000-000000000001" }
    });

    const skuSelect = screen.getByLabelText("Select Item / SKU", { selector: "select" });
    fireEvent.change(skuSelect, {
      target: { value: "0190f8aa-0000-7000-8000-000000000001" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Add SKU" }));

    await waitFor(() => {
      expect(screen.getByText("Total Mix: 100.00%")).toBeDefined();
    });

    const calcBtn = screen.getByRole("button", { name: "Calculate Required Quantities" });
    fireEvent.click(calcBtn);

    await waitFor(() => {
      expect(screen.getByText(/Unable to resolve active price for SKU SKU-NOPRICE/i)).toBeDefined();
    });
  });
});
