import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { businessToday } from "../../shell/i18n/formats";
import type { DailyShopReport, Location, MonthlyShopReport } from "./reportingApi";
import { ShopReportsPage } from "./ShopReportsPage";
import { ShopReportsDashboardCard } from "./ShopReportsDashboardCard";

const LOCATION_ID = "0190f8bb-0000-7000-8000-000000000001";

const mockLocation: Location = {
  locationId: LOCATION_ID,
  ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
  locationCode: "S101",
  locationType: "SHOP",
  nameEn: "Colombo Main Branch",
  connectivitySpecMet: true,
  status: "ACTIVE"
};

const mockDailyReport: DailyShopReport = {
  summary: {
    businessDate: "2026-10-23",
    totalGrossSales: "120000.00",
    totalNetSales: "100000.00",
    totalTaxAmount: "20000.00",
    totalTransactions: 50,
    averageTransactionValue: "2400.00",
    totalItemsSold: "150.000",
    totalTargetAmount: "100000.00",
    totalVarianceAmount: "20000.00",
    overallAchievementPercent: "120.0",
    currency: "LKR"
  },
  shops: [
    {
      locationId: LOCATION_ID,
      locationCode: "S101",
      locationName: "Colombo Main Branch",
      businessDate: "2026-10-23",
      grossSales: "120000.00",
      netSales: "100000.00",
      taxAmount: "20000.00",
      transactionCount: 50,
      averageTransactionValue: "2400.00",
      itemsSold: "150.000",
      targetAmount: "100000.00",
      varianceAmount: "20000.00",
      achievementPercent: "120.0",
      performanceState: "TARGET_MET",
      currency: "LKR"
    }
  ]
};

const mockMonthlyReport: MonthlyShopReport = {
  summary: {
    yearMonth: "2026-10",
    periodStart: "2026-10-01",
    periodEnd: "2026-10-31",
    totalGrossSales: "2500000.00",
    totalNetSales: "2200000.00",
    totalTaxAmount: "300000.00",
    totalTransactions: 1200,
    averageTransactionValue: "2083.33",
    totalItemsSold: "3500.000",
    totalTargetAmount: "2400000.00",
    totalVarianceAmount: "100000.00",
    overallAchievementPercent: "104.2",
    currency: "LKR"
  },
  dailyTrend: [
    {
      businessDate: "2026-10-01",
      grossSales: "80000.00",
      netSales: "70000.00",
      taxAmount: "10000.00",
      transactionCount: 40,
      averageTransactionValue: "2000.00",
      itemsSold: "110.000"
    }
  ],
  shops: [
    {
      locationId: LOCATION_ID,
      locationCode: "S101",
      locationName: "Colombo Main Branch",
      grossSales: "2500000.00",
      netSales: "2200000.00",
      taxAmount: "300000.00",
      transactionCount: 1200,
      averageTransactionValue: "2083.33",
      itemsSold: "3500.000",
      targetAmount: "2400000.00",
      varianceAmount: "100000.00",
      achievementPercent: "104.2",
      performanceState: "TARGET_MET",
      currency: "LKR"
    }
  ]
};

const api = {
  dailyShopReport: vi.fn(async () => mockDailyReport),
  monthlyShopReport: vi.fn(async () => mockMonthlyReport),
  locations: vi.fn(async () => [mockLocation])
};

vi.mock("./reportingApi", async () => {
  const actual = await vi.importActual<object>("./reportingApi");
  return {
    ...actual,
    useReportingApi: () => api
  };
});

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/reporting/shop-reports" element={<ShopReportsPage />} />
            <Route path="/reporting/dashboard-card" element={<ShopReportsDashboardCard />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("ShopReportsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(cleanup);

  it("renders daily shop report with KPIs and table breakdown", async () => {
    renderAt("/reporting/shop-reports");

    await waitFor(() => {
      expect(screen.getByText("Total Gross Sales")).toBeDefined();
    });
    expect(screen.getAllByText(/120,000.00/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("120.0%").length).toBeGreaterThanOrEqual(1);

    // Check table row
    expect(screen.getAllByText("Colombo Main Branch").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("S101")).toBeDefined();
    expect(screen.getByText("Target Met")).toBeDefined();
  });

  it("switches to monthly report tab and displays trend and cross-shop tables", async () => {
    renderAt("/reporting/shop-reports");

    await waitFor(() => {
      expect(screen.getByText("Monthly Shop Report")).toBeDefined();
    });

    const dailyTab = screen.getByRole("tab", { name: "Daily Shop Report" });
    const monthlyTab = screen.getByRole("tab", { name: "Monthly Shop Report" });
    expect(dailyTab.className).toContain("shop-reports-tab--active");
    expect(monthlyTab.className).not.toContain("shop-reports-tab--active");

    fireEvent.click(monthlyTab);

    expect(monthlyTab.className).toContain("shop-reports-tab--active");
    expect(dailyTab.className).not.toContain("shop-reports-tab--active");

    await waitFor(() => {
      expect(screen.getByText("Cross-Shop Performance Comparison")).toBeDefined();
      expect(screen.getByText("Daily Sales Trend for the Month")).toBeDefined();
    });

    expect(screen.getAllByText(/2,500,000.00/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("01/10/2026")).toBeDefined();
  });

  it("filters daily report by selected shop location", async () => {
    renderAt("/reporting/shop-reports");

    // Wait for locations query to resolve and populate select options
    await waitFor(() => {
      expect(screen.getByText("S101 Colombo Main Branch")).toBeDefined();
    });

    const select = screen.getByLabelText("Shop");
    fireEvent.change(select, { target: { value: LOCATION_ID } });

    await waitFor(() => {
      expect(api.dailyShopReport).toHaveBeenCalledWith(
        expect.objectContaining({ locationId: LOCATION_ID })
      );
    });
  });

  it("filters daily report via custom ShopReportSelect listbox option", async () => {
    renderAt("/reporting/shop-reports");

    await waitFor(() => {
      expect(screen.getByText("S101 Colombo Main Branch")).toBeDefined();
    });

    fireEvent.click(screen.getByRole("button", { name: "All Permitted Shops" }));
    fireEvent.click(within(screen.getByRole("listbox")).getByRole("option", { name: "S101 Colombo Main Branch" }));

    await waitFor(() => {
      expect(api.dailyShopReport).toHaveBeenCalledWith(
        expect.objectContaining({ locationId: LOCATION_ID })
      );
    });
  });

  it("selects a date via custom ShopReportDatePicker (.cdp) calendar and closes on Escape", async () => {
    const todayStr = businessToday();
    const [year, month] = todayStr.split("-");
    const formattedToday = `${todayStr.slice(8, 10)}/${month}/${year}`;
    const expectedPickedIso = `${year}-${month}-15`;
    const expectedPickedDisplay = `15/${month}/${year}`;

    renderAt("/reporting/shop-reports");

    await waitFor(() => {
      expect(screen.getByRole("button", { name: formattedToday })).toBeDefined();
    });

    fireEvent.click(screen.getByRole("button", { name: formattedToday }));
    const dialog = screen.getByRole("dialog", { name: "Date" });
    expect(dialog.className).toContain("cdp__popup");
    fireEvent.click(within(dialog).getByRole("button", { name: "15" }));

    expect(screen.queryByRole("dialog", { name: "Date" })).toBeNull();
    expect(screen.getByRole("button", { name: expectedPickedDisplay })).toBeDefined();
    await waitFor(() => {
      expect(api.dailyShopReport).toHaveBeenCalledWith(
        expect.objectContaining({ date: expectedPickedIso })
      );
    });

    fireEvent.click(screen.getByRole("button", { name: expectedPickedDisplay }));
    expect(screen.getByRole("dialog", { name: "Date" })).toBeDefined();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Date" })).toBeNull();
  });

  it("selects a month via custom ShopReportMonthPicker (.cdp) dialog on the Monthly tab", async () => {
    const todayStr = businessToday();
    const [currentYearStr, currentMonthStr] = todayStr.split("-");
    const prevYearNum = Number(currentYearStr) - 1;
    const currentMonthLabel = `${messages.en[`reporting.shop_reports.month.${Number(currentMonthStr)}`]} ${currentYearStr}`;

    renderAt("/reporting/shop-reports");

    await waitFor(() => {
      expect(screen.getByText("Monthly Shop Report")).toBeDefined();
    });

    fireEvent.click(screen.getByRole("tab", { name: "Monthly Shop Report" }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: currentMonthLabel })).toBeDefined();
    });

    fireEvent.click(screen.getByRole("button", { name: currentMonthLabel }));
    const dialog = screen.getByRole("dialog", { name: "Month" });
    expect(dialog.className).toContain("cdp__popup");
    fireEvent.click(within(dialog).getByRole("button", { name: "Previous year" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "October" }));

    expect(screen.queryByRole("dialog", { name: "Month" })).toBeNull();
    expect(screen.getByRole("button", { name: `October ${prevYearNum}` })).toBeDefined();
    await waitFor(() => {
      expect(api.monthlyShopReport).toHaveBeenCalledWith(
        expect.objectContaining({ yearMonth: `${prevYearNum}-10` })
      );
    });

    fireEvent.click(screen.getByRole("button", { name: `October ${prevYearNum}` }));
    expect(screen.getByRole("dialog", { name: "Month" })).toBeDefined();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Month" })).toBeNull();
  });

  it("renders ShopReportsDashboardCard with today's summary metrics", async () => {
    renderAt("/reporting/dashboard-card");

    await waitFor(() => {
      expect(screen.getByText("Daily Shop Operations")).toBeDefined();
    });

    expect(screen.getByText(/120,000.00/)).toBeDefined();
    expect(screen.getByText("50")).toBeDefined();
    expect(screen.getByText("View shop reports")).toBeDefined();
  });
});
