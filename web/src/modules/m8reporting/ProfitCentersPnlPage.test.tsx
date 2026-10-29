import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type {
  Location,
  MonthlyProfitCenterPnlReport,
  ProfitCenter
} from "./reportingApi";
import { ProfitCentersPnlPage } from "./ProfitCentersPnlPage";

const mockLocations: Location[] = [
  {
    locationId: "0190f8bb-0000-7000-8000-000000000001",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    locationCode: "LOC-01",
    locationType: "SHOP",
    nameEn: "Polgolla Agency Shop",
    connectivitySpecMet: true,
    status: "ACTIVE"
  }
];

const mockProfitCenters: ProfitCenter[] = [
  {
    profitCenterId: "0190f8dd-0000-7000-8000-000000000001",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    code: "AG-01",
    name: "Polgolla Agency A",
    category: "AGENCY",
    locationId: "0190f8bb-0000-7000-8000-000000000001",
    locationName: "Polgolla Agency Shop",
    status: "ACTIVE",
    createdAt: "2026-10-01T00:00:00Z"
  },
  {
    profitCenterId: "0190f8dd-0000-7000-8000-000000000002",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    code: "AG-02",
    name: "Kandy Agency B",
    category: "AGENCY",
    status: "ACTIVE",
    createdAt: "2026-10-01T00:00:00Z"
  }
];

const mockPnlReportSettled: MonthlyProfitCenterPnlReport = {
  month: "2026-10",
  currency: "LKR",
  summary: {
    totalRevenue: "500000.00",
    totalCost: "350000.00",
    grossProfit: "150000.00",
    marginPercent: "30.00",
    costPending: false
  },
  items: [
    {
      profitCenterId: "0190f8dd-0000-7000-8000-000000000001",
      code: "AG-01",
      name: "Polgolla Agency A",
      category: "AGENCY",
      locationId: "0190f8bb-0000-7000-8000-000000000001",
      locationName: "Polgolla Agency Shop",
      txnCount: 120,
      revenue: "300000.00",
      cost: "210000.00",
      grossProfit: "90000.00",
      marginPercent: "30.00",
      costPending: false
    },
    {
      profitCenterId: "0190f8dd-0000-7000-8000-000000000002",
      code: "AG-02",
      name: "Kandy Agency B",
      category: "AGENCY",
      txnCount: 80,
      revenue: "150000.00",
      cost: "105000.00",
      grossProfit: "45000.00",
      marginPercent: "30.00",
      costPending: false
    }
  ],
  unallocated: {
    profitCenterId: "00000000-0000-0000-0000-000000000000",
    code: "UNALLOCATED",
    name: "Unallocated Shop Sales (No Profit Center Mapping)",
    category: "OTHER",
    txnCount: 20,
    revenue: "50000.00",
    cost: "35000.00",
    grossProfit: "15000.00",
    marginPercent: "30.00",
    costPending: false
  }
};

const mockPnlReportPending: MonthlyProfitCenterPnlReport = {
  month: "2026-10",
  currency: "LKR",
  summary: {
    totalRevenue: "300000.00",
    costPending: true
  },
  items: [
    {
      profitCenterId: "0190f8dd-0000-7000-8000-000000000001",
      code: "AG-01",
      name: "Polgolla Agency A",
      category: "AGENCY",
      locationId: "0190f8bb-0000-7000-8000-000000000001",
      locationName: "Polgolla Agency Shop",
      txnCount: 120,
      revenue: "300000.00",
      costPending: true
    }
  ]
};

const api = {
  listProfitCenters: vi.fn(async () => mockProfitCenters),
  createProfitCenter: vi.fn(async () => mockProfitCenters[0]),
  monthlyProfitCenterPnl: vi.fn(async () => mockPnlReportSettled),
  locations: vi.fn(async () => mockLocations)
};

vi.mock("./reportingApi", async () => {
  const actual = await vi.importActual<object>("./reportingApi");
  return {
    ...actual,
    useReportingApi: () => api
  };
});

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (perm: string) => perm === "rpt.profit_center.manage" || perm === "rpt.profit_center.view"
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/reporting/profit-centers"]}>
          <Routes>
            <Route path="/reporting/profit-centers" element={<ProfitCentersPnlPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("ProfitCentersPnlPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(cleanup);

  it("renders multi-agency comparison table and reconciles totals", async () => {
    api.monthlyProfitCenterPnl.mockResolvedValueOnce(mockPnlReportSettled);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Total Revenue")).toBeDefined();
    });

    // Check KPIs
    expect(screen.getAllByText(/500,000.00/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/350,000.00/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/150,000.00/).length).toBeGreaterThanOrEqual(1);

    // Check agencies are displayed separately
    expect(screen.getByText("Polgolla Agency A")).toBeDefined();
    expect(screen.getByText("Kandy Agency B")).toBeDefined();
    expect(screen.getByText("AG-01")).toBeDefined();
    expect(screen.getByText("AG-02")).toBeDefined();

    // Check unallocated row
    expect(screen.getByText("UNALLOCATED")).toBeDefined();
    expect(screen.getAllByText(/50,000.00/).length).toBeGreaterThanOrEqual(1);

    // Check combined total reconciliation row
    expect(screen.getByText("Combined Total (Reconciled to Posted Sales)")).toBeDefined();
    expect(screen.getByText("220")).toBeDefined(); // 120 + 80 + 20
  });

  it("surfaces pending/unresolved cost explicitly without overstating profit", async () => {
    api.monthlyProfitCenterPnl.mockResolvedValueOnce(mockPnlReportPending);
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeDefined();
    });

    // Warning banner present
    expect(
      screen.getByText(/Some stock movements or sales lack confirmed cost lot records/)
    ).toBeDefined();

    // Pending chips present
    expect(screen.getAllByText("Pending").length).toBeGreaterThanOrEqual(1);
  });

  it("opens create profit center modal and triggers mutation", async () => {
    api.monthlyProfitCenterPnl.mockResolvedValueOnce(mockPnlReportSettled);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("New Profit Center")).toBeDefined();
    });

    fireEvent.click(screen.getByText("New Profit Center"));

    expect(screen.getByText("Create Profit Center")).toBeDefined();
    expect(screen.getByLabelText("Code")).toBeDefined();
    expect(screen.getByLabelText("Name")).toBeDefined();

    fireEvent.change(screen.getByLabelText("Code"), { target: { value: "PACK-01" } });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Polgolla Packing" } });

    fireEvent.click(screen.getByText("Save Profit Center"));

    await waitFor(() => {
      expect(api.createProfitCenter).toHaveBeenCalledWith(
        expect.objectContaining({
          code: "PACK-01",
          name: "Polgolla Packing",
          category: "AGENCY"
        }),
        expect.any(String)
      );
    });
  });
});
