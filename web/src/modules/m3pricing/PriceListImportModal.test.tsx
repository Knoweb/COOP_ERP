import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { PriceListImportReport } from "./pricingApi";
import { PriceListImportModal } from "./PriceListImportModal";

const LIST_ID = "0190e770-0000-7000-8000-000000000001";

const SUCCESS_REPORT: PriceListImportReport = {
  priceListId: LIST_ID,
  saved: true,
  totalRows: 2,
  importedLines: 2,
  errorRows: 0,
  problems: [],
  outcomes: [
    {
      skuId: "0190e770-0000-7000-8000-000000000101",
      uomCode: "EA",
      tierFromQty: 0,
      ok: true,
      reason: null,
      review: null,
      ceilingKind: null,
      ceilingValue: null,
      ceilingRef: null
    }
  ]
};

const ERROR_REPORT: PriceListImportReport = {
  priceListId: LIST_ID,
  saved: false,
  totalRows: 2,
  importedLines: 0,
  errorRows: 1,
  problems: [
    {
      line: 2,
      sku: "UNKNOWN-SKU",
      field: "sku",
      message: "m3.price_list.import.sku_not_found"
    }
  ],
  outcomes: []
};

const api = {
  importPriceListLines: vi.fn(async () => SUCCESS_REPORT)
};

vi.mock("./pricingApi", () => ({ usePricingApi: () => api }));
vi.mock("../../shell/api/idempotency", () => ({
  useIdempotencyKey: () => ({
    current: () => "mock-idempotency-key",
    next: vi.fn()
  })
}));

describe("PriceListImportModal (CAT-13)", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } }
    });
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  function renderModal(props: Partial<React.ComponentProps<typeof PriceListImportModal>> = {}) {
    return render(
      <QueryClientProvider client={queryClient}>
        <IntlProvider locale="en" messages={messages.en}>
          <MemoryRouter>
            <PriceListImportModal
              isOpen={true}
              onClose={vi.fn()}
              listId={LIST_ID}
              listName="Wholesale Draft List"
              kind="TRADE"
              onImportSuccess={vi.fn()}
              {...props}
            />
          </MemoryRouter>
        </IntlProvider>
      </QueryClientProvider>
    );
  }

  it("renders modal with dropzone, description, and template link", () => {
    renderModal();

    expect(screen.getByRole("dialog")).toBeTruthy();
    expect(screen.getByText("Import price list lines from file")).toBeTruthy();
    expect(screen.getByText(/Wholesale Draft List/)).toBeTruthy();
    expect(screen.getByText("Choose or drop CSV file")).toBeTruthy();
    expect(screen.getByText("Download sample CSV template")).toBeTruthy();
    const submitBtn = screen.getByRole("button", { name: "Import to draft" }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(true);
  });

  it("handles file upload and calls importPriceListLines", async () => {
    const onImportSuccess = vi.fn();
    renderModal({ onImportSuccess });

    const csvContent = "sku,uom,tier,price\nSKU-001,EA,0,100.00\nSKU-002,EA,0,250.00\n";
    const file = new File([csvContent], "pricelist.csv", { type: "text/csv" });

    const fileInput = screen.getByLabelText("Choose or drop CSV file");
    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByText(/pricelist\.csv/)).toBeTruthy();
    });

    const submitBtn = screen.getByRole("button", { name: "Import to draft" }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(false);

    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(api.importPriceListLines).toHaveBeenCalledWith(
        LIST_ID,
        { sheet: csvContent, replace: true },
        "mock-idempotency-key"
      );
      expect(onImportSuccess).toHaveBeenCalled();
      expect(screen.getByRole("status").textContent).toContain("Successfully imported 2 lines to draft list.");
    });
  });

  it("displays validation problem table on failed import", async () => {
    api.importPriceListLines.mockResolvedValueOnce(ERROR_REPORT);
    renderModal();

    const csvContent = "sku,price\nUNKNOWN-SKU,100.00\n";
    const file = new File([csvContent], "bad.csv", { type: "text/csv" });

    const fileInput = screen.getByLabelText("Choose or drop CSV file");
    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByText(/bad\.csv/)).toBeTruthy();
    });

    fireEvent.click(screen.getByRole("button", { name: "Import to draft" }));

    await waitFor(() => {
      expect(screen.getByText("Import failed with 1 problem.")).toBeTruthy();
      expect(screen.getByText("#2")).toBeTruthy();
      expect(screen.getByText("UNKNOWN-SKU")).toBeTruthy();
      expect(screen.getByText("Item not found in catalogue")).toBeTruthy();
    });
  });
});
