import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { PriceList } from "./pricingApi";
import { ShelfPricesPage } from "./ShelfPricesPage";

const RETAIL_LIST: PriceList = {
  priceListId: "0190e760-0000-7000-8000-000000000001",
  rootPriceListId: "0190e760-0000-7000-8000-000000000001",
  ownerEntityId: "0190e760-0000-7000-8000-000000000010",
  kind: "RETAIL",
  name: "Standard Shelf Prices",
  version: 1,
  status: "PUBLISHED",
  applyFrom: "2026-10-01",
  createdAt: "2026-10-01T00:00:00Z"
};

const COMMERCIAL_LIST: PriceList = {
  priceListId: "0190e760-0000-7000-8000-000000000002",
  rootPriceListId: "0190e760-0000-7000-8000-000000000002",
  ownerEntityId: "0190e760-0000-7000-8000-000000000010",
  kind: "COMMERCIAL",
  name: "Commercial Bulk Prices",
  version: 1,
  status: "DRAFT",
  createdAt: "2026-10-01T00:00:00Z"
};

const api = {
  listPriceLists: vi.fn(async (kind?: PriceList["kind"]): Promise<PriceList[]> => {
    if (kind === "RETAIL") return [RETAIL_LIST];
    if (kind === "COMMERCIAL") return [];
    if (kind === "ADVISORY") return [];
    return [];
  }),
  createPriceList: vi.fn(async (name: string, key?: string, kind?: PriceList["kind"]): Promise<PriceList> => {
    void key;
    return {
      priceListId: "0190e760-0000-7000-8000-000000000099",
      rootPriceListId: "0190e760-0000-7000-8000-000000000099",
      ownerEntityId: "0190e760-0000-7000-8000-000000000010",
      kind: kind ?? "TRADE",
      name,
      version: 1,
      status: "DRAFT",
      createdAt: "2026-10-01T00:00:00Z"
    };
  })
};

vi.mock("./pricingApi", () => ({ usePricingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: () => true }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/pricing/shelf"]}>
          <Routes>
            <Route path="/pricing/shelf" element={<ShelfPricesPage />} />
            <Route path="/pricing/shelf/:listId" element={<div data-testid="detail-page" />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("ShelfPricesPage - Commercial Price Authoring", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(cleanup);

  it("allows selecting COMMERCIAL kind and authoring a commercial price list even when a retail list exists", async () => {
    renderPage();

    // Table lists the existing retail list
    expect(await screen.findByText("Standard Shelf Prices")).toBeTruthy();

    // Form is available because hasCommercial is false
    const kindSelect = await screen.findByRole("combobox");
    // Since hasRetail is true, COMMERCIAL is selected by default or available
    expect(kindSelect).toBeTruthy();

    fireEvent.change(kindSelect, { target: { value: "COMMERCIAL" } });

    const nameInput = screen.getByRole("textbox", { name: text("pricing.field.name") });
    fireEvent.change(nameInput, { target: { value: "Wholesale Commercial Price List" } });

    const createButton = screen.getByRole("button", { name: text("pricing.shelf.create") });
    fireEvent.click(createButton);

    await waitFor(() => {
      expect(api.createPriceList).toHaveBeenCalledWith(
        "Wholesale Commercial Price List",
        expect.any(String),
        "COMMERCIAL"
      );
    });

    // Navigates to the drafted list
    expect(await screen.findByTestId("detail-page")).toBeTruthy();
  });

  it("displays COMMERCIAL price lists in the table with localized kind text", async () => {
    api.listPriceLists.mockImplementation(async (kind?: PriceList["kind"]) => {
      if (kind === "COMMERCIAL") return [COMMERCIAL_LIST];
      return [];
    });

    renderPage();

    expect(await screen.findByText("Commercial Bulk Prices")).toBeTruthy();
    expect(screen.getByText(text("pricing.kind.commercial"))).toBeTruthy();
  });
});
