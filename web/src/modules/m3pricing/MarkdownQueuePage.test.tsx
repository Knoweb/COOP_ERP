import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { ExpirySlowMovingDiscount, ExpirySlowMovingEvaluation, Location } from "./pricingApi";
import { MarkdownQueuePage } from "./MarkdownQueuePage";

const SHOP_ID = "0190e760-0000-7000-8000-000000000101";
const SKU_1 = "0190e760-0000-7000-8000-000000000201";
const SKU_2 = "0190e760-0000-7000-8000-000000000202";

const LOCATIONS: Location[] = [
  {
    locationId: SHOP_ID,
    ownerEntityId: "0190e760-0000-7000-8000-000000000010",
    locationCode: "S01",
    locationType: "SHOP",
    nameEn: "Main Shop",
    status: "ACTIVE",
    connectivitySpecMet: true
  }
];

const DISCOUNTS: ExpirySlowMovingDiscount[] = [
  {
    skuId: SKU_1,
    skuCode: "RICE-SAMBA-01",
    stockLotId: "0190e760-0000-7000-8000-000000000301",
    batchId: "0190e760-0000-7000-8000-000000000401",
    batchNo: "BATCH-2026-001",
    locationId: SHOP_ID,
    reason: "NEAR_EXPIRY",
    tierOrClassification: "CRITICAL",
    basePrice: 200.0,
    markdownPercent: 30.0,
    discountedPrice: 140.0,
    qtyOnHand: 15.0,
    expiryDate: "2026-11-05",
    daysToExpiry: 3,
    validFrom: "2026-11-02",
    validTo: "2026-11-05",
    status: "ACTIVE"
  },
  {
    skuId: SKU_2,
    skuCode: "SPICE-CHILLI-02",
    locationId: SHOP_ID,
    reason: "SLOW_MOVING",
    tierOrClassification: "STAGNANT",
    basePrice: 100.0,
    markdownPercent: 20.0,
    discountedPrice: 80.0,
    qtyOnHand: 50.0,
    validFrom: "2026-11-02",
    status: "ACTIVE"
  }
];

const EVAL_RESULT: ExpirySlowMovingEvaluation = {
  locationId: SHOP_ID,
  totalEvaluated: 2,
  markdownsActive: 2,
  nearExpiryCount: 1,
  slowMovingCount: 1,
  evaluatedAt: "2026-11-02T10:00:00Z"
};

const api = {
  locations: vi.fn(async () => LOCATIONS),
  listExpirySlowMovingDiscounts: vi.fn(async () => DISCOUNTS),
  evaluateExpirySlowMovingDiscounts: vi.fn(async () => EVAL_RESULT)
};

vi.mock("./pricingApi", () => ({ usePricingApi: () => api }));
vi.mock("../../shell/scope/useScope", () => ({
  useScope: () => ({ entityId: "0190e760-0000-7000-8000-000000000010", locationId: SHOP_ID })
}));
vi.mock("../../shell/api/idempotency", () => ({
  useIdempotencyKey: () => ({
    current: () => "mock-idempotency-key",
    next: vi.fn()
  })
}));
vi.mock("./SkuPicker", () => ({
  SkuName: ({ skuId }: { skuId: string }) => <span data-testid={`sku-${skuId}`}>SKU-{skuId}</span>
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <MarkdownQueuePage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("MarkdownQueuePage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders page header and items from authoritative backend markdowns", async () => {
    renderPage();

    await waitFor(() => {
      expect(api.listExpirySlowMovingDiscounts).toHaveBeenCalledWith(SHOP_ID, undefined, "ACTIVE");
    });

    expect(await screen.findByText("CRITICAL")).toBeTruthy();
    expect(await screen.findByText("STAGNANT")).toBeTruthy();
    expect(screen.getByText("BATCH-2026-001")).toBeTruthy();
  });

  it("filters items when search query is entered", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("CRITICAL")).toBeTruthy();
    });

    const searchInput = screen.getByRole("searchbox");
    fireEvent.change(searchInput, { target: { value: "SPICE" } });

    expect(screen.queryByText("BATCH-2026-001")).toBeNull();
    expect(screen.getByText("STAGNANT")).toBeTruthy();
  });

  it("triggers evaluate mutation when evaluate button is clicked", async () => {
    renderPage();

    const evalButton = await screen.findByRole("button", { name: /Evaluate Markdowns/i });
    await waitFor(() => {
      expect(evalButton.hasAttribute("disabled")).toBe(false);
    });

    fireEvent.click(evalButton);

    await waitFor(() => {
      expect(api.evaluateExpirySlowMovingDiscounts).toHaveBeenCalledWith(SHOP_ID, "mock-idempotency-key");
    });

    expect(await screen.findByText(/Evaluated: 2 active markdowns found\./i)).toBeTruthy();
  });
});
