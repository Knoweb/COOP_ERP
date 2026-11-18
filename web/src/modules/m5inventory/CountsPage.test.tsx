import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Location } from "./inventoryApi";
import { CountsPage } from "./CountsPage";

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

const state = {
  permissions: new Set<string>(["inv.stock.view", "inv.count.schedule", "shop.count.record"])
};

const api = {
  locations: vi.fn(async () => mockLocations),
  counts: vi.fn(async () => []),
  balances: vi.fn(async () => []),
  negativeLots: vi.fn(async () => []),
  scheduleCount: vi.fn(),
  startCount: vi.fn(),
  acknowledgeNegativeLot: vi.fn()
};

vi.mock("./inventoryApi", () => ({
  useInventoryApi: () => api
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderCounts(locale: "en" | "si" | "ta" = "en") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale={locale} messages={messages[locale]}>
        <MemoryRouter initialEntries={["/inventory/counts"]}>
          <Routes>
            <Route path="/inventory/counts" element={<CountsPage />} />
            <Route path="/inventory/stock-take" element={<div data-testid="mobile-stock-take-route">Mobile Stock-Taking Page</div>} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("CountsPage Mobile Stock-Taking navigation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["inv.stock.view", "inv.count.schedule", "shop.count.record"]);
  });

  afterEach(() => {
    cleanup();
  });

  it("navigates to /inventory/stock-take when clicking the Mobile Stock-Taking link", () => {
    renderCounts("en");

    const link = screen.getByRole("link", { name: "Mobile Stock-Taking" });
    expect(link.getAttribute("href")).toBe("/inventory/stock-take");

    fireEvent.click(link);
    expect(screen.getByTestId("mobile-stock-take-route")).toBeTruthy();
  });

  it("renders localized labels in Sinhala and Tamil", () => {
    for (const locale of ["si", "ta"] as const) {
      cleanup();
      renderCounts(locale);
      const expectedLabel = (messages[locale] as Record<string, string>)["inventory.stock_take.link"];
      const link = screen.getByRole("link", { name: expectedLabel });
      expect(link.getAttribute("href")).toBe("/inventory/stock-take");
    }
  });

  it("hides the Mobile Stock-Taking link when shop.count.record permission is missing", () => {
    state.permissions = new Set(["inv.stock.view"]);
    renderCounts("en");

    expect(screen.queryByRole("link", { name: "Mobile Stock-Taking" })).toBeNull();
  });
});
