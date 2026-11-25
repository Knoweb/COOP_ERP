import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Location, LotBalance, WriteOff } from "./inventoryApi";
import { WriteOffsPage } from "./WriteOffsPage";

const WAREHOUSE = "0190f0de-0000-7000-8000-000000000101";

const mockLocations: Location[] = [
  {
    locationId: WAREHOUSE,
    ownerEntityId: "0190f000-0000-7000-8000-000000000001",
    locationCode: "FW01",
    locationType: "WAREHOUSE",
    nameEn: "Central Warehouse",
    status: "ACTIVE"
  } as unknown as Location
];

const mockLots: LotBalance[] = [
  {
    stockLotId: "lot-1",
    locationId: WAREHOUSE,
    skuId: "sku-1",
    batchId: "batch-1",
    batchNo: "BAT-001",
    condition: "GOOD",
    qtyOnHand: 10,
    unitCost: 100,
    negative: false,
    expired: false
  }
];

const state = {
  permissions: new Set<string>(["inv.writeoff.request", "inv.writeoff.witness", "inv.writeoff.approve"]),
  writeOffs: [] as WriteOff[]
};

const api = {
  locations: vi.fn(async () => mockLocations),
  writeOffs: vi.fn(async () => state.writeOffs),
  balances: vi.fn(async () => mockLots),
  requestWriteOff: vi.fn()
};

vi.mock("./inventoryApi", () => ({
  useInventoryApi: () => api
}));

vi.mock("./SkuLabel", () => ({
  SkuLabel: ({ skuId }: { skuId: string }) => <span data-testid="sku-label">{skuId}</span>
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <WriteOffsPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("WriteOffsPage Category Dropdown", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("portals the category listbox, selects Spoilage, and retains selection", async () => {
    renderPage();

    // Wait for locations and balances to load
    await waitFor(() => {
      expect(screen.getByText("New write-off")).toBeTruthy();
    });

    const categorySelect = document.getElementById("writeoff-category") as HTMLSelectElement;
    expect(categorySelect).toBeTruthy();
    expect(categorySelect.value).toBe("");

    // Trigger button for Category dropdown
    const categoryLabel = screen.getByText("Category of loss").closest("label")!;
    const trigger = within(categoryLabel).getByRole("button");
    fireEvent.click(trigger);

    // Listbox should be portalled to document.body
    const listbox = screen.getByRole("listbox");
    expect(listbox).toBeTruthy();
    expect(document.body.contains(listbox)).toBe(true);

    // Click Spoilage
    const spoilageOption = within(listbox).getByRole("option", { name: "Spoilage" });
    fireEvent.click(spoilageOption);

    // Listbox closes and trigger updates
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(categorySelect.value).toBe("SPOILAGE");
    expect(trigger.textContent).toContain("Spoilage");
  });

  it("updates and retains selection when changing to Waste", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("New write-off")).toBeTruthy();
    });

    const categorySelect = document.getElementById("writeoff-category") as HTMLSelectElement;
    const categoryLabel = screen.getByText("Category of loss").closest("label")!;
    const trigger = within(categoryLabel).getByRole("button");

    // First select Spoilage
    fireEvent.click(trigger);
    let listbox = screen.getByRole("listbox");
    fireEvent.click(within(listbox).getByRole("option", { name: "Spoilage" }));
    expect(categorySelect.value).toBe("SPOILAGE");
    expect(trigger.textContent).toContain("Spoilage");

    // Reopen and select Waste
    fireEvent.click(trigger);
    listbox = screen.getByRole("listbox");
    fireEvent.click(within(listbox).getByRole("option", { name: "Waste" }));

    // Verifies Waste is selected and retained
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(categorySelect.value).toBe("WASTE");
    expect(trigger.textContent).toContain("Waste");
  });
});
