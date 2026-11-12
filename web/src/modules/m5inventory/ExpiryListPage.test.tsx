import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { ExpiryAlertItem, SlowMovingStock } from "./inventoryApi";
import { ExpiryListPage } from "./ExpiryListPage";

const SHOP = "0190f0de-0000-7000-8000-000000000132";
const SKU_1 = "0190f6ab-0000-7000-8000-000000000001";
const SKU_2 = "0190f6ab-0000-7000-8000-000000000002";

const mockExpiryItem: ExpiryAlertItem = {
  stockLotId: "0190f6ac-0000-7000-8000-000000000001",
  locationId: SHOP,
  skuId: SKU_1,
  batchId: "0190f6ad-0000-7000-8000-000000000001",
  batchNo: "BATCH-001",
  expiryDate: "2026-11-05",
  daysToExpiry: 4,
  qtyOnHand: 25.0,
  unitCost: 100.0,
  urgencyTier: "CRITICAL",
  alertActive: true
};

const mockSlowMovingItem: SlowMovingStock = {
  locationId: SHOP,
  skuId: SKU_2,
  qtyOnHand: 80.0,
  unitCost: 150.0,
  daysWithoutSale: 35,
  lastSaleDate: "2026-09-25",
  salesInWindow: 0.0,
  averageDailySales: 0.0,
  daysOfSupply: 999.0,
  classification: "STAGNANT",
  alertActive: true
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
  permissions: new Set<string>(["inv.expiry.scan", "inv.stock.view"])
};

const api = {
  locations: vi.fn(async () => [location(SHOP, "SHOP 1")]),
  expiryFeed: vi.fn(async () => [mockExpiryItem]),
  slowMovingStock: vi.fn(async () => [mockSlowMovingItem]),
  scanExpiryAlerts: vi.fn(async () => ({
    nearExpiryAlertsEmitted: 1,
    slowMovingAlertsEmitted: 1,
    totalEvaluated: 2
  }))
};

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));
vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));
vi.mock("./SkuLabel", () => ({ SkuLabel: ({ skuId }: { skuId: string }) => <span>{skuId}</span> }));

type TestLocale = "en" | "si" | "ta";
const tierTranslationCases = (["en", "si", "ta"] as const).flatMap((locale) =>
  (["EXPIRED", "CRITICAL", "WARNING", "APPROACHING", "HEALTHY"] as const).map(
    (tier) => [locale, tier] as const
  )
);

function renderPage(locale: TestLocale = "en") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale={locale} messages={messages[locale]}>
        <MemoryRouter>
          <ExpiryListPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];
const localizedText = (locale: TestLocale, id: string) =>
  (messages[locale] as Record<string, string>)[id];

describe("ExpiryListPage", () => {
  beforeEach(() => {
    api.locations.mockReset().mockResolvedValue([location(SHOP, "SHOP 1")]);
    api.expiryFeed.mockReset().mockResolvedValue([mockExpiryItem]);
    api.slowMovingStock.mockReset().mockResolvedValue([mockSlowMovingItem]);
    api.scanExpiryAlerts.mockReset().mockResolvedValue({
      nearExpiryAlertsEmitted: 1,
      slowMovingAlertsEmitted: 1,
      totalEvaluated: 2
    });
    state.permissions = new Set<string>(["inv.expiry.scan", "inv.stock.view"]);
  });
  afterEach(cleanup);

  it("renders location picker and displays KPI cards and near-expiry items", async () => {
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    expect(select).toBeDefined();

    fireEvent.change(select, { target: { value: SHOP } });

    await waitFor(() => {
      expect(api.expiryFeed).toHaveBeenCalled();
    });

    // Check KPIs rendered
    expect(await screen.findByText(text("inventory.expiry.kpi_critical"))).toBeDefined();
    expect(await screen.findByText(text("inventory.expiry.kpi_slow"))).toBeDefined();

    // Check near-expiry table row rendered
    expect(await screen.findByText(SKU_1)).toBeDefined();
    expect(await screen.findByText("BATCH-001")).toBeDefined();
    expect(await screen.findByText("Critical")).toBeDefined();
  });

  it.each(tierTranslationCases)(
    "renders the translated %s %s expiry tier instead of its message id",
    async (locale, tier) => {
      api.expiryFeed.mockResolvedValueOnce([{ ...mockExpiryItem, urgencyTier: tier }]);
      renderPage(locale);

      const select = (await screen.findByRole("combobox", {
        name: localizedText(locale, "inventory.field.location")
      })) as HTMLSelectElement;
      fireEvent.change(select, { target: { value: SHOP } });

      expect(
        await screen.findByText(localizedText(locale, `inventory.expiry.tier.${tier}`), {
          selector: ".state-chip"
        })
      ).toBeDefined();
      expect(screen.queryByText(`inventory.expiry.tier.${tier}`)).toBeNull();
    }
  );

  it.each(["en", "si", "ta"] as const)(
    "describes the %s approaching filter using the implemented 60-day boundary",
    (locale) => {
      const label = localizedText(locale, "inventory.expiry.filter_approaching");
      expect(label).toContain("31");
      expect(label).toContain("60");
      expect(label).not.toContain("90");
    }
  );

  it.each(["CRITICAL", "WARNING", "APPROACHING", "EXPIRED"] as const)(
    "passes the %s tier filter to the expiry API",
    async (tier) => {
      renderPage();
      const locationSelect = (await screen.findByRole("combobox", {
        name: text("inventory.field.location")
      })) as HTMLSelectElement;
      fireEvent.change(locationSelect, { target: { value: SHOP } });
      await waitFor(() => expect(api.expiryFeed).toHaveBeenCalled());
      api.expiryFeed.mockClear();

      fireEvent.change(
        screen.getByRole("combobox", { name: text("inventory.expiry.filter_tier") }),
        { target: { value: tier } }
      );

      await waitFor(() => {
        expect(api.expiryFeed).toHaveBeenCalledWith(SHOP, undefined, tier);
      });
    }
  );

  it("shows expiry loading, error, and empty states", async () => {
    api.expiryFeed.mockImplementation(() => new Promise<ExpiryAlertItem[]>(() => {}));
    const loadingView = renderPage();
    fireEvent.change(
      await screen.findByRole("combobox", { name: text("inventory.field.location") }),
      { target: { value: SHOP } }
    );
    expect(await screen.findByText(text("inventory.loading"))).toBeDefined();
    loadingView.unmount();

    api.expiryFeed.mockReset().mockRejectedValue(new Error("expiry unavailable"));
    const errorView = renderPage();
    fireEvent.change(
      await screen.findByRole("combobox", { name: text("inventory.field.location") }),
      { target: { value: SHOP } }
    );
    expect((await screen.findAllByRole("alert")).length).toBeGreaterThan(0);
    errorView.unmount();

    api.expiryFeed.mockReset().mockResolvedValue([]);
    renderPage();
    fireEvent.change(
      await screen.findByRole("combobox", { name: text("inventory.field.location") }),
      { target: { value: SHOP } }
    );
    expect(await screen.findByText(text("inventory.expiry.none"))).toBeDefined();
  });

  it("shows slow-moving loading, error, and empty states", async () => {
    api.slowMovingStock.mockImplementation(() => new Promise<SlowMovingStock[]>(() => {}));
    const loadingView = renderPage();
    fireEvent.change(
      await screen.findByRole("combobox", { name: text("inventory.field.location") }),
      { target: { value: SHOP } }
    );
    fireEvent.click(
      await screen.findByRole("button", { name: text("inventory.expiry.tab_slow_moving") })
    );
    expect(await screen.findByText(text("inventory.loading"))).toBeDefined();
    loadingView.unmount();

    api.slowMovingStock.mockReset().mockRejectedValue(new Error("slow-moving unavailable"));
    const errorView = renderPage();
    fireEvent.change(
      await screen.findByRole("combobox", { name: text("inventory.field.location") }),
      { target: { value: SHOP } }
    );
    fireEvent.click(
      await screen.findByRole("button", { name: text("inventory.expiry.tab_slow_moving") })
    );
    expect(await screen.findByRole("alert")).toBeDefined();
    errorView.unmount();

    api.slowMovingStock.mockReset().mockResolvedValue([]);
    renderPage();
    fireEvent.change(
      await screen.findByRole("combobox", { name: text("inventory.field.location") }),
      { target: { value: SHOP } }
    );
    fireEvent.click(
      await screen.findByRole("button", { name: text("inventory.expiry.tab_slow_moving") })
    );
    expect(await screen.findByText(text("inventory.slow.none"))).toBeDefined();
  });

  it("switches to slow-moving stock tab and renders stagnant items", async () => {
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: SHOP } });

    const slowTabButton = await screen.findByRole("button", {
      name: text("inventory.expiry.tab_slow_moving")
    });
    fireEvent.click(slowTabButton);

    await waitFor(() => {
      expect(api.slowMovingStock).toHaveBeenCalledWith(SHOP);
    });

    expect(await screen.findByText(SKU_2)).toBeDefined();
    expect(await screen.findByText("Stagnant")).toBeDefined();
  });

  it("triggers manual scan & emit alerts action when inv.expiry.scan permission is held", async () => {
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: SHOP } });

    const scanButton = await screen.findByRole("button", {
      name: text("inventory.expiry.scan_button")
    });
    fireEvent.click(scanButton);

    await waitFor(() => {
      expect(api.scanExpiryAlerts).toHaveBeenCalledWith(SHOP, expect.any(String));
    });

    expect(
      await screen.findByText("Scan complete: 1 near-expiry, 1 slow-moving alerts emitted.")
    ).toBeDefined();
  });

  it("hides manual scan button when inv.expiry.scan permission is absent", async () => {
    state.permissions = new Set<string>(["inv.stock.view"]); // scan permission revoked
    renderPage();

    const select = (await screen.findByRole("combobox", {
      name: text("inventory.field.location")
    })) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: SHOP } });

    await waitFor(() => {
      expect(api.expiryFeed).toHaveBeenCalled();
    });

    expect(screen.queryByRole("button", { name: text("inventory.expiry.scan_button") })).toBeNull();
  });
});
