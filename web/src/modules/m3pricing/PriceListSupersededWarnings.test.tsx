import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { PriceList, PriceListDetail } from "./pricingApi";
import { TradePriceListPage } from "./TradePriceListPage";
import { ShelfListPage } from "./ShelfListPage";

type Kind = "TRADE" | "RETAIL";

const base: PriceList = {
  priceListId: "current",
  rootPriceListId: "root",
  ownerEntityId: "owner",
  kind: "TRADE",
  name: "Regression price list",
  version: 2,
  status: "PUBLISHED",
  createdAt: "2026-10-01T00:00:00Z"
};

let selected: PriceList = { ...base };

const source: PriceList = {
  ...base,
  priceListId: "source",
  version: 1,
  status: "SUPERSEDED"
};

const api = {
  getPriceList: vi.fn(async (id: string): Promise<PriceListDetail> => {
    if (id === "current") {
      return { list: selected, lines: [] };
    }
    if (id === "source") {
      return { list: source, lines: [] };
    }
    throw new Error(`Unexpected price-list id: ${id}`);
  }),
  listPriceLists: vi.fn(async (): Promise<PriceList[]> => []),
  advisoryLines: vi.fn(async () => []),
  sellerRelationships: vi.fn(async () => []),
  draftNewVersion: vi.fn(async () => ({
    ...selected,
    priceListId: "new-draft",
    status: "DRAFT" as const
  }))
};

vi.mock("./pricingApi", () => ({
  usePricingApi: () => api
}));
vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: () => true
}));
vi.mock("./ListRelationships", () => ({
  ListRelationships: () => null
}));
vi.mock("./SkuPicker", () => ({
  SkuPicker: () => null,
  SkuName: () => null
}));
vi.mock("./PricingTabs", () => ({
  PricingTabs: () => null
}));
vi.mock("./ShelfLabelPrintAction", () => ({
  ShelfLabelPrintAction: () => null
}));

const message = (id: string) =>
  (messages.en as Record<string, string>)[id];

function renderList(kind: Kind, status: PriceList["status"]) {
  selected = {
    ...base,
    kind,
    status,
    sourceVersionId: status === "DRAFT" ? "source" : null
  };

  const path =
    kind === "TRADE"
      ? "/pricing/lists/current"
      : "/pricing/shelf/current";

  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });

  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/pricing/lists/:listId"
              element={<TradePriceListPage />}
            />
            <Route
              path="/pricing/shelf/:listId"
              element={<ShelfListPage />}
            />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("PRC-11 superseded price-list warnings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    selected = { ...base };
  });

  afterEach(cleanup);

  for (const kind of ["TRADE", "RETAIL"] as const) {
    it(`${kind}: warns before drafting from a superseded version`, async () => {
      renderList(kind, "SUPERSEDED");

      const warning = await screen.findByRole("alert");
      expect(warning.textContent).toBe(
        message("pricing.warning.superseded_source")
      );

      expect(
        screen.getByRole("button", {
          name: message("pricing.next_version")
        })
      ).toBeTruthy();
    });

    it(`${kind}: warns when a draft was copied from a superseded version`, async () => {
      renderList(kind, "DRAFT");

      const warning = await screen.findByRole("alert");
      expect(warning.textContent).toBe(
        message("pricing.warning.draft_from_superseded")
      );

      expect(api.getPriceList).toHaveBeenCalledWith("source");
    });

    it(`${kind}: does not warn when viewing a published version`, async () => {
      renderList(kind, "PUBLISHED");

      expect(await screen.findByText("Regression price list")).toBeTruthy();
      expect(screen.queryByRole("alert")).toBeNull();
    });
  }
});
