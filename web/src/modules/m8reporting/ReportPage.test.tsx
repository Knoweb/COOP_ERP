import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { ReportPage } from "./ReportPage";

const mockApi = {
  definitions: vi.fn(),
  locations: vi.fn(),
  runs: vi.fn(),
  data: vi.fn(),
  csv: vi.fn(),
  requestRun: vi.fn(),
  run: vi.fn()
};

vi.mock("./reportingApi", () => ({
  useReportingApi: () => mockApi
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: () => true
}));

function renderReport(path = "/reporting/reports/invoices-issued") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/reporting/reports/:reportId" element={<ReportPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("ReportPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.definitions.mockResolvedValue([
      {
        reportId: "invoices-issued",
        titleId: "m8.report.invoices_issued.title",
        decisionId: "m8.report.invoices_issued.decision",
        period: true,
        location: true
      }
    ]);
    mockApi.locations.mockResolvedValue([
      { locationId: "loc-1", locationCode: "W01", nameEn: "Federation Warehouse", locationType: "WAREHOUSE" }
    ]);
    mockApi.runs.mockResolvedValue([]);
    mockApi.data.mockResolvedValue({
      freshness: "2026-10-19T03:37:00Z",
      columns: [
        { key: "invoiceNo", labelId: "m8.col.invoice_no", kind: "TEXT" },
        { key: "gross", labelId: "m8.col.gross", kind: "MONEY" }
      ],
      rows: [{ invoiceNo: "INV-001", gross: "2500.00" }],
      totals: { gross: "2500.00" }
    });
  });

  afterEach(cleanup);

  it("renders PageHeader, back pill, parameter controls with custom location picker, report table, and earlier prints", async () => {
    renderReport();

    expect(await screen.findByRole("heading", { level: 1, name: text("m8.report.invoices_issued.title") })).toBeTruthy();
    expect(screen.getByRole("link", { name: text("reporting.back") }).getAttribute("href")).toBe("/reporting");
    expect(screen.getByText(text("m8.report.invoices_issued.decision"))).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: text("reporting.field.location.all") }));
    const listbox = screen.getByRole("listbox");
    fireEvent.click(await within(listbox).findByRole("option", { name: /Federation Warehouse/ }));

    fireEvent.click(screen.getByRole("button", { name: text("reporting.show") }));

    expect(await screen.findByText("INV-001")).toBeTruthy();
    expect(screen.getAllByText("Rs 2,500.00")).toHaveLength(2);
    expect(screen.getByRole("heading", { level: 2, name: text("reporting.runs.title") })).toBeTruthy();
    expect(screen.getByText(text("reporting.runs.empty"))).toBeTruthy();
  });
});

