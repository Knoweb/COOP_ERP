import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { BulkStockReport } from "./inventoryApi";
import { NewOpeningBalancePage } from "./NewOpeningBalancePage";

const SHOP = "0190f0de-0000-7000-8000-000000000132";

const location = (locationId: string, locationCode: string) => ({
  locationId,
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  locationCode,
  locationType: "SHOP" as const,
  nameEn: locationCode,
  status: "ACTIVE" as const
});

const mockPreviewReport: BulkStockReport = {
  importId: "0190f6ac-0000-7000-8000-000000000001",
  status: "VALID",
  totalRows: 2,
  importedLines: 0,
  validRows: 2,
  errorRows: 0,
  totalValuation: 2500.0,
  previewRows: [
    {
      lineNo: 1,
      sku: "DHAL1",
      skuCode: "DHAL1",
      itemName: "Red Split Lentils",
      baseUom: "KG",
      qty: 10,
      unitCost: 150,
      extendedCost: 1500,
      batchNo: "B101",
      expiryDate: "2027-05-31",
      printedMrp: 180,
      condition: "GOOD",
      status: "VALID"
    },
    {
      lineNo: 2,
      sku: "SUGAR",
      skuCode: "SUGAR",
      itemName: "White Sugar",
      baseUom: "KG",
      qty: 10,
      unitCost: 100,
      extendedCost: 1000,
      batchNo: "B102",
      expiryDate: "2026-12-31",
      printedMrp: 120,
      condition: "GOOD",
      status: "VALID"
    }
  ]
};

const api = {
  locations: vi.fn(async () => [location(SHOP, "SHOP 1")]),
  downloadOpeningStockTemplate: vi.fn(async () => new Blob(["dummy-xlsx"], { type: "application/vnd.openxmlformats" })),
  bulkImportOpeningStock: vi.fn(async () => mockPreviewReport),
  prepare: vi.fn(async () => ({ openingBalanceId: "0190f6ac-0000-7000-8000-000000000099" })),
  searchSkus: vi.fn(async () => [])
};

vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));
vi.mock("../../shell/api/idempotency", () => ({
  useIdempotencyKey: () => ({ current: () => "0190f6ab-0000-7000-8000-000000000001", next: vi.fn() })
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <NewOpeningBalancePage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("NewOpeningBalancePage (PGL-M2-06)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(cleanup);

  it("renders tab navigation with Excel import default and downloads template", async () => {
    renderPage();

    expect(screen.getByRole("tab", { name: text("inventory.opening.tab.excel") })).toBeDefined();
    expect(screen.getByRole("tab", { name: text("inventory.opening.tab.manual") })).toBeDefined();

    const downloadBtn = screen.getByRole("button", { name: text("inventory.opening.template.download") });
    expect(downloadBtn).toBeDefined();

    fireEvent.click(downloadBtn);
    expect(api.downloadOpeningStockTemplate).toHaveBeenCalled();
  });

  it("switches to manual mode and shows manual entry table", async () => {
    renderPage();

    const manualTab = screen.getByRole("tab", { name: text("inventory.opening.tab.manual") });
    fireEvent.click(manualTab);

    expect(screen.getByText(text("inventory.opening.lines.empty"))).toBeDefined();
    expect(screen.getByRole("button", { name: text("inventory.opening.prepare") })).toBeDefined();
  });

  it("handles spreadsheet validation and displays preview card with rows", async () => {
    renderPage();

    // Select location
    const locationSelect = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(locationSelect, { target: { value: SHOP } });

    // Simulate file drop
    const dropzone = screen.getByText(text("inventory.opening.upload.drop"));
    const file = new File(["sku,qty,cost\nDHAL1,10,150"], "test.csv", { type: "text/csv" });
    fireEvent.drop(dropzone, {
      dataTransfer: { files: [file] }
    });

    await waitFor(() => {
      expect(screen.getByText(/test\.csv/)).toBeDefined();
    });

    // Click validate
    const validateBtn = screen.getByRole("button", { name: text("inventory.opening.btn.validate") });
    await waitFor(() => {
      expect((validateBtn as HTMLButtonElement).disabled).toBe(false);
    });
    fireEvent.click(validateBtn);

    await waitFor(() => {
      expect(api.bulkImportOpeningStock).toHaveBeenCalledWith(
        SHOP,
        "VALIDATE",
        expect.any(String),
        undefined,
        expect.any(String)
      );
    });

    // Preview table rendered
    await waitFor(() => {
      expect(screen.getByText(text("inventory.opening.preview.summary"))).toBeDefined();
      expect(screen.getByText("DHAL1")).toBeDefined();
      expect(screen.getByText("SUGAR")).toBeDefined();
    });
  });
});
