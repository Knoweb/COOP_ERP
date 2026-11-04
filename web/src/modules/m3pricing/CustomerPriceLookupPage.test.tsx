import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { CustomerPriceLookup, Location } from "./pricingApi";
import { CustomerPriceLookupPage } from "./CustomerPriceLookupPage";

const SHOP_A: Location = {
  locationId: "0190e760-0000-7000-8000-000000000101",
  ownerEntityId: "0190e760-0000-7000-8000-000000000001",
  locationCode: "SHOP-COL-01",
  nameEn: "Colombo Central Cooperative Shop",
  locationType: "SHOP",
  connectivitySpecMet: true,
  status: "ACTIVE"
};

const SHOP_B: Location = {
  locationId: "0190e760-0000-7000-8000-000000000102",
  ownerEntityId: "0190e760-0000-7000-8000-000000000001",
  locationCode: "SHOP-KAN-02",
  nameEn: "Kandy Cooperative Shop",
  locationType: "SHOP",
  connectivitySpecMet: true,
  status: "ACTIVE"
};

const RICE_ITEM: CustomerPriceLookup = {
  skuId: "0190e760-0000-7000-8000-000000000201",
  skuCode: "RICE-SAMBA-1KG",
  productName: "Samba Rice 1kg",
  uom: "KG",
  publicPrice: 215.0,
  currency: "LKR",
  isAvailable: true,
  controlPrice: 220.0,
  isPriceControlled: true,
  locationId: SHOP_A.locationId,
  locationName: SHOP_A.nameEn,
  effectiveDate: "2026-11-04"
};

const SUGAR_ITEM: CustomerPriceLookup = {
  skuId: "0190e760-0000-7000-8000-000000000202",
  skuCode: "WHITE-SUGAR-1KG",
  productName: "White Sugar 1kg",
  uom: "KG",
  publicPrice: 240.0,
  currency: "LKR",
  isAvailable: true,
  controlPrice: null,
  isPriceControlled: false,
  locationId: SHOP_A.locationId,
  locationName: SHOP_A.nameEn,
  effectiveDate: "2026-11-04"
};

const OUT_OF_STOCK_ITEM: CustomerPriceLookup = {
  skuId: "0190e760-0000-7000-8000-000000000203",
  skuCode: "IMPORTED-MILK-400G",
  productName: "Milk Powder 400g",
  uom: "EA",
  publicPrice: 950.0,
  currency: "LKR",
  isAvailable: false,
  controlPrice: null,
  isPriceControlled: false,
  locationId: SHOP_A.locationId,
  locationName: SHOP_A.nameEn,
  effectiveDate: "2026-11-04"
};

const api = {
  locations: vi.fn(async (): Promise<Location[]> => [SHOP_A, SHOP_B]),
  searchCustomerPrices: vi.fn(async (_locId: string, q?: string): Promise<CustomerPriceLookup[]> => {
    if (!q || q.trim() === "") return [RICE_ITEM, SUGAR_ITEM];
    if (q.toLowerCase().includes("rice")) return [RICE_ITEM];
    if (q.toLowerCase().includes("sugar")) return [SUGAR_ITEM];
    if (q.toLowerCase().includes("milk")) return [OUT_OF_STOCK_ITEM];
    return [];
  }),
  lookupCustomerPrice: vi.fn(
    async (_locId: string, params: { barcode?: string; skuCode?: string }): Promise<CustomerPriceLookup | null> => {
      if (params.barcode === "479000100010" || params.skuCode === "RICE-SAMBA-1KG") return RICE_ITEM;
      return null;
    }
  )
};

vi.mock("./pricingApi", () => ({ usePricingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: () => true }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <IntlProvider locale="en" messages={messages.en}>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/pricing/customer-lookup"]}>
          <Routes>
            <Route path="/pricing/customer-lookup" element={<CustomerPriceLookupPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </IntlProvider>
  );
}

describe("CustomerPriceLookupPage (M3-08)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders page header and selects default shop", async () => {
    renderPage();
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading.textContent).toBe("Customer Price Lookup");

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement;
    expect(select).toBeTruthy();
    await waitFor(() => {
      expect(select.value).toBe(SHOP_A.locationId);
    });
  });

  it("searches and displays verified product retail prices with control price notice", async () => {
    renderPage();

    await screen.findByRole("combobox");

    const searchInput = screen.getByRole("searchbox");
    fireEvent.change(searchInput, { target: { value: "rice" } });

    const submitBtn = screen.getByRole("button", { name: "Search prices" });
    fireEvent.click(submitBtn);

    expect(await screen.findByText("Samba Rice 1kg")).toBeTruthy();
    expect(screen.getByText("RICE-SAMBA-1KG")).toBeTruthy();
    expect(screen.getByText("LKR 215.00")).toBeTruthy();
    expect(screen.getByText("per KG")).toBeTruthy();
    expect(screen.getByText("Available")).toBeTruthy();
    expect(screen.getByText("Government Gazetted Maximum Price: LKR 220.00")).toBeTruthy();
  });

  it("displays unavailable status badge when product is out of stock / not sellable", async () => {
    renderPage();

    await screen.findByRole("combobox");

    const searchInput = screen.getByRole("searchbox");
    fireEvent.change(searchInput, { target: { value: "milk" } });

    const submitBtn = screen.getByRole("button", { name: "Search prices" });
    fireEvent.click(submitBtn);

    expect(await screen.findByText("Milk Powder 400g")).toBeTruthy();
    expect(screen.getByText("Currently Unavailable")).toBeTruthy();
    expect(screen.getByText("LKR 950.00")).toBeTruthy();
  });

  it("displays empty results notice when search has no matches", async () => {
    renderPage();

    await screen.findByRole("combobox");

    const searchInput = screen.getByRole("searchbox");
    fireEvent.change(searchInput, { target: { value: "nonexistent item" } });

    const submitBtn = screen.getByRole("button", { name: "Search prices" });
    fireEvent.click(submitBtn);

    expect(await screen.findByText("No products found matching your search at this shop.")).toBeTruthy();
  });

  it("strictly enforces privacy: no internal cost, margin, or owner financial data on the page", async () => {
    renderPage();

    await screen.findByRole("combobox");

    const searchInput = screen.getByRole("searchbox");
    fireEvent.change(searchInput, { target: { value: "rice" } });
    fireEvent.click(screen.getByRole("button", { name: "Search prices" }));

    expect(await screen.findByText("Samba Rice 1kg")).toBeTruthy();

    const card = screen.getByText("Samba Rice 1kg").closest(".customer-price-card");
    expect(card).toBeTruthy();
    const cardText = card?.textContent || "";
    expect(cardText).not.toMatch(/wholesale/i);
    expect(cardText).not.toMatch(/margin/i);
    expect(cardText).not.toMatch(/cost/i);
    expect(cardText).not.toMatch(/batch id/i);
    expect(cardText).not.toMatch(/price list id/i);
    expect(cardText).not.toMatch(/0190e760/i);

    // Verify consumer protection privacy banner
    expect(
      screen.getByText(
        "Consumer Notice: Displayed prices are final and tax-inclusive. Internal wholesale margins and commercial costs are protected."
      )
    ).toBeTruthy();
  });
});
