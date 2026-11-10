import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { ReorderControl, Sku } from "./inventoryApi";
import { ReorderControlsPage } from "./ReorderControlsPage";

const SHOP = "0190f0de-0000-7000-8000-000000000132";
const SKU_1 = "0190f6ab-0000-7000-8000-000000000001";

const mockSku: Sku = {
  skuId: SKU_1,
  skuCode: "SKU-VD5DUZU6",
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  status: "LOCAL",
  nameEn: "Test Samba Rice 5kg Pack",
  baseUomCode: "EA",
  soldByWeight: false,
  batchTracked: true,
  expiryTracked: false,
  hasPrintedMrp: false,
  taxCategoryId: "0190f0de-0000-7000-8000-000000000099",
  multiMrpPolicy: "AUTO_LOWEST",
  originKind: "PURCHASED"
};

const mockReorderControl: ReorderControl = {
  reorderControlId: "0190f6ac-0000-7000-8000-000000000001",
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  locationId: SHOP,
  skuId: SKU_1,
  reorderLevel: 20.0,
  reorderQty: 50.0,
  maxQty: 100.0,
  alertTriggered: true,
  lastAlertedAt: "2026-10-29T10:00:00Z",
  currentOnHandQty: 15.0,
  currentAvailableQty: 15.0
};

const location = (locationId: string, locationCode: string) => ({
  locationId,
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  locationCode,
  locationType: "SHOP" as const,
  nameEn: locationCode,
  status: "ACTIVE" as const
});

const state = {
  permissions: new Set<string>(["inv.reorder.manage", "inv.stock.view"])
};

const api = {
  listReorderControls: vi.fn(async () => [mockReorderControl]),
  configureReorderControl: vi.fn(async () => mockReorderControl),
  deleteReorderControl: vi.fn(async () => ({ success: true })),
  evaluateLowStock: vi.fn(async () => ({ evaluatedCount: 1, alertsTriggeredCount: 1 })),
  searchSkus: vi.fn(async () => [mockSku]),
  getSku: vi.fn(async () => mockSku),
  locations: vi.fn(async () => [location(SHOP, "SHOP 1")])
};

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));
vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));
vi.mock("./SkuLabel", () => ({ SkuLabel: ({ skuId }: { skuId: string }) => <span>{skuId}</span> }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <ReorderControlsPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("ReorderControlsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set<string>(["inv.reorder.manage", "inv.stock.view"]);
  });
  afterEach(cleanup);

  it("renders location picker and reorder controls table", async () => {
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    expect(select).toBeDefined();

    fireEvent.change(select, { target: { value: SHOP } });

    await waitFor(() => {
      expect(api.listReorderControls).toHaveBeenCalledWith(SHOP);
    });

    expect(await screen.findByText(SKU_1)).toBeDefined();
    expect(await screen.findByText(text("inventory.reorder.status_low"))).toBeDefined();
  });

  it("triggers manual low-stock evaluation", async () => {
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: SHOP } });

    const evalButton = await screen.findByRole("button", { name: text("inventory.reorder.evaluate") });
    fireEvent.click(evalButton);

    await waitFor(() => {
      expect(api.evaluateLowStock).toHaveBeenCalledWith(SHOP, undefined, expect.any(String));
    });
  });

  it("searches by code or name and submits the selected SKU UUID", async () => {
    renderPage();

    fireEvent.change(await screen.findByRole("combobox", { name: text("inventory.field.location") }), {
      target: { value: SHOP }
    });
    fireEvent.click(await screen.findByRole("button", { name: text("inventory.reorder.add") }));

    const findInput = screen.getByRole("searchbox", { name: text("inventory.field.find_item") });
    fireEvent.change(findInput, { target: { value: "Samba Rice" } });
    fireEvent.submit(findInput.closest("form")!);

    await waitFor(() => expect(api.searchSkus).toHaveBeenCalledWith("Samba Rice"));
    const result = await screen.findByRole("button", {
      name: "SKU-VD5DUZU6 — Test Samba Rice 5kg Pack"
    });
    fireEvent.click(result);

    fireEvent.change(screen.getByLabelText(text("inventory.reorder.level")), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText(text("inventory.reorder.qty")), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: text("inventory.reorder.save") }));

    await waitFor(() => {
      expect(api.configureReorderControl).toHaveBeenCalledWith(
        {
          locationId: SHOP,
          skuId: SKU_1,
          reorderLevel: 20,
          reorderQty: 10,
          maxQty: undefined
        },
        expect.any(String)
      );
    });
  });

  it("keeps the SKU selected while editing an existing control", async () => {
    renderPage();

    fireEvent.change(await screen.findByRole("combobox", { name: text("inventory.field.location") }), {
      target: { value: SHOP }
    });
    fireEvent.click(await screen.findByRole("button", { name: text("inventory.reorder.edit") }));

    expect(await screen.findByText("SKU-VD5DUZU6 — Test Samba Rice 5kg Pack")).toBeDefined();
    expect((screen.getByRole("button", { name: text("inventory.reorder.save") }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("does not allow saving until a catalogue result is selected", async () => {
    renderPage();

    fireEvent.change(await screen.findByRole("combobox", { name: text("inventory.field.location") }), {
      target: { value: SHOP }
    });
    fireEvent.click(await screen.findByRole("button", { name: text("inventory.reorder.add") }));

    const save = screen.getByRole("button", { name: text("inventory.reorder.save") }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.submit(save.closest("form")!);
    expect(api.configureReorderControl).not.toHaveBeenCalled();
  });

  it("allows deleting a reorder control", async () => {
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: SHOP } });

    const deleteBtn = await screen.findByRole("button", { name: text("inventory.reorder.delete") });
    fireEvent.click(deleteBtn);

    await waitFor(() => {
      expect(api.deleteReorderControl).toHaveBeenCalledWith(SHOP, SKU_1, expect.any(String));
    });
  });

  it("hides mutating actions when user lacks inv.reorder.manage permission", async () => {
    state.permissions = new Set<string>(["inv.stock.view"]);
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: SHOP } });

    await waitFor(() => {
      expect(api.listReorderControls).toHaveBeenCalledWith(SHOP);
    });

    expect(screen.queryByRole("button", { name: text("inventory.reorder.add") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("inventory.reorder.evaluate") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("inventory.reorder.edit") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("inventory.reorder.delete") })).toBeNull();
  });
});
