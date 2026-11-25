import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { DashboardPage } from "./DashboardPage";

const mockApi = {
  dashboard: vi.fn(),
  definitions: vi.fn(),
  exceptions: vi.fn(),
  dailyShopReport: vi.fn(),
  getShopTargetPerformance: vi.fn()
};

vi.mock("./reportingApi", () => ({
  useReportingApi: () => mockApi
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: () => true
}));

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <DashboardPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("DashboardPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.dashboard.mockResolvedValue({
      freshness: "2026-10-19T03:37:00Z",
      tiles: [
        {
          tileId: "sales",
          labelId: "m8.tile.sales",
          kind: "MONEY",
          value: "12500.00",
          drillReportId: "trade-by-distributor",
          trend: [
            { from: "2026-10-05", value: "4200.00" },
            { from: "2026-10-12", value: "12500.00" }
          ]
        },
        {
          tileId: "exceptions",
          labelId: "m8.tile.exceptions",
          kind: "COUNT",
          value: "2",
          drillReportId: "exceptions"
        }
      ]
    });
    mockApi.definitions.mockResolvedValue([
      {
        reportId: "stock-position",
        titleId: "m8.report.stock_position.title",
        decisionId: "m8.report.stock_position.decision"
      }
    ]);
    mockApi.exceptions.mockResolvedValue([]);
    mockApi.dailyShopReport.mockResolvedValue({ summary: null });
    mockApi.getShopTargetPerformance.mockResolvedValue({ items: [] });
  });

  afterEach(cleanup);

  it("renders PageHeader, freshness badge, KPI tiles with 8-week trend, exceptions section, and report cards", async () => {
    renderDashboard();

    expect(await screen.findByRole("heading", { level: 1, name: text("reporting.dashboard.title") })).toBeTruthy();

    const salesTile = await screen.findByRole("listitem", { name: text("m8.tile.sales") });
    expect(within(salesTile).getByText("Rs 12,500.00")).toBeTruthy();
    expect(within(salesTile).getByRole("img", { name: text("reporting.trend.label") })).toBeTruthy();
    expect(within(salesTile).getByRole("link", { name: text("reporting.dashboard.open") }).getAttribute("href")).toBe(
      "/reporting/reports/trade-by-distributor"
    );

    const excTile = screen.getByRole("listitem", { name: text("m8.tile.exceptions") });
    expect(within(excTile).getByRole("link", { name: text("reporting.exceptions.open") }).getAttribute("href")).toBe(
      "/reporting/exceptions"
    );

    expect(screen.getByRole("heading", { level: 2, name: text("reporting.exceptions.title") })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: text("reporting.reports.title") })).toBeTruthy();

    const reportLink = await screen.findByRole("link", { name: text("m8.report.stock_position.title") });
    expect(reportLink.getAttribute("href")).toBe("/reporting/reports/stock-position");
    expect(screen.getByText(text("m8.report.stock_position.decision"))).toBeTruthy();
  });
});

