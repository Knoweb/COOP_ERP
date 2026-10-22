import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Location, ShopTargetPerformanceItem } from "./reportingApi";
import { ShopTargetsPage } from "./ShopTargetsPage";
import { ShopTargetsDashboardCard } from "./ShopTargetsDashboardCard";

const TARGET_ID = "0190f8aa-0000-7000-8000-000000000001";
const LOCATION_ID = "0190f8bb-0000-7000-8000-000000000001";

const state = { permissions: new Set<string>() };

const mockLocation: Location = {
  locationId: LOCATION_ID,
  ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
  locationCode: "S101",
  locationType: "SHOP",
  nameEn: "Colombo Main Branch",
  connectivitySpecMet: true,
  status: "ACTIVE"
};

const mockPerformanceItem: ShopTargetPerformanceItem = {
  targetId: TARGET_ID,
  locationId: LOCATION_ID,
  locationCode: "S101",
  locationName: "Colombo Main Branch",
  periodStart: "2026-10-01",
  periodEnd: "2026-10-31",
  targetAmount: "100000.00",
  actualAmount: "125000.00",
  varianceAmount: "25000.00",
  achievementPercent: "125.0",
  currency: "LKR",
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z"
};

const api = {
  getShopTargetPerformance: vi.fn(async () => ({ items: [mockPerformanceItem] })),
  locations: vi.fn(async () => [mockLocation]),
  createShopTarget: vi.fn(async () => ({
    targetId: TARGET_ID,
    locationId: LOCATION_ID,
    periodStart: "2026-11-01",
    periodEnd: "2026-11-30",
    targetAmount: "200000.00",
    currency: "LKR",
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z"
  })),
  updateShopTarget: vi.fn(async () => ({
    ...mockPerformanceItem,
    targetAmount: "150000.00"
  })),
  getShopTarget: vi.fn(async () => mockPerformanceItem)
};

vi.mock("./reportingApi", async () => {
  const actual = await vi.importActual<object>("./reportingApi");
  return {
    ...actual,
    useReportingApi: () => api
  };
});

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/reporting/targets" element={<ShopTargetsPage />} />
            <Route path="/reporting/dashboard-card" element={<ShopTargetsDashboardCard />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("ShopTargetsPage", () => {
  beforeEach(() => {
    state.permissions.clear();
    state.permissions.add("rpt.target.view");
    vi.clearAllMocks();
  });

  afterEach(cleanup);

  it("renders targets page with summary KPIs and table data", async () => {
    renderAt("/reporting/targets");

    expect(await screen.findByText("Colombo Main Branch")).toBeDefined();
    expect(screen.getByText("Shop Targets & Actual Sales")).toBeDefined();
    expect(screen.getAllByText("125.0%")).toHaveLength(2);
    expect(screen.getByText("Target Met")).toBeDefined();

    // With view permission only, manage buttons are hidden
    expect(screen.queryByText("New target")).toBeNull();
    expect(screen.queryByText("Edit target")).toBeNull();
  });

  it("shows action buttons when user holds rpt.target.manage and submits creation modal", async () => {
    state.permissions.add("rpt.target.manage");
    renderAt("/reporting/targets");

    const newTargetBtn = await screen.findByText("New target");
    expect(newTargetBtn).toBeDefined();

    const editBtn = await screen.findByText("Edit target");
    expect(editBtn).toBeDefined();

    // Open modal
    fireEvent.click(newTargetBtn);

    // Modal fields
    const startInput = screen.getByLabelText("Period Start");
    const endInput = screen.getByLabelText("Period End");
    const amountInput = screen.getByLabelText("Target Amount (LKR)");

    fireEvent.change(startInput, { target: { value: "2026-11-01" } });
    fireEvent.change(endInput, { target: { value: "2026-11-30" } });
    fireEvent.change(amountInput, { target: { value: "200000.00" } });

    const saveBtn = screen.getByText("Save Target");
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(api.createShopTarget).toHaveBeenCalledWith(
        expect.objectContaining({
          locationId: LOCATION_ID,
          periodStart: "2026-11-01",
          periodEnd: "2026-11-30",
          targetAmount: "200000.00"
        }),
        expect.any(String)
      );
    });
  });

  it("renders dashboard summary card with aggregated target performance", async () => {
    renderAt("/reporting/dashboard-card");

    expect(await screen.findByText("Shop sales targets")).toBeDefined();
    expect(screen.getByText("View target performance")).toBeDefined();
    expect(screen.getByText("125.0%")).toBeDefined();
  });
});
