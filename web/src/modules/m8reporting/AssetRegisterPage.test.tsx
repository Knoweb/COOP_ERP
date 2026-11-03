import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Asset, Location } from "./reportingApi";
import { AssetRegisterPage } from "./AssetRegisterPage";

const mockLocations: Location[] = [
  {
    locationId: "0190f8bb-0000-7000-8000-000000000001",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    locationCode: "LOC-01",
    locationType: "SHOP",
    nameEn: "Polgolla Coop City",
    connectivitySpecMet: true,
    status: "ACTIVE"
  }
];

const mockAssets: Asset[] = [
  {
    assetId: "0190f8ee-0000-7000-8000-000000000001",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    assetCode: "AST-001",
    name: "Coop City Refrigerated Display",
    description: "Cold storage unit in main aisle",
    assetType: "EQUIPMENT",
    locationId: "0190f8bb-0000-7000-8000-000000000001",
    locationName: "Polgolla Coop City",
    acquisitionDate: "2025-06-15",
    acquisitionCost: "125000.00",
    status: "ACTIVE",
    createdAt: "2025-06-15T08:00:00Z"
  },
  {
    assetId: "0190f8ee-0000-7000-8000-000000000002",
    ownerEntityId: "0190f8cc-0000-7000-8000-000000000001",
    assetCode: "AST-002",
    name: "Head Office Land",
    description: "Main society premises",
    assetType: "LAND",
    status: "ACTIVE",
    createdAt: "2024-01-01T00:00:00Z"
  }
];

let mockCanManage = true;

const api = {
  listAssets: vi.fn(async () => mockAssets),
  registerAsset: vi.fn(async () => mockAssets[0]),
  locations: vi.fn(async () => mockLocations)
};

vi.mock("./reportingApi", async () => {
  const actual = await vi.importActual<object>("./reportingApi");
  return {
    ...actual,
    useReportingApi: () => api
  };
});

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (perm: string) => {
    if (perm === "rpt.asset.manage") return mockCanManage;
    return true;
  }
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/reporting/assets"]}>
          <Routes>
            <Route path="/reporting/assets" element={<AssetRegisterPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("AssetRegisterPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockCanManage = true;
    api.listAssets.mockResolvedValue(mockAssets);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders asset register table with rows and summary KPIs", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Asset Register & Branch View")).toBeDefined();
      expect(screen.getByText("AST-001")).toBeDefined();
      expect(screen.getByText("Coop City Refrigerated Display")).toBeDefined();
      expect(screen.getByText("Polgolla Coop City")).toBeDefined();
      expect(screen.getByText("Head Office / Entity-wide")).toBeDefined();
    });

    expect(screen.getByText("Total Assets")).toBeDefined();
    expect(screen.getAllByText("2").length).toBeGreaterThanOrEqual(1);
  });

  it("filters assets by location", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("LOC-01 Polgolla Coop City")).toBeDefined();
    });

    const locationSelect = screen.getByLabelText("Location");
    fireEvent.change(locationSelect, {
      target: { value: "0190f8bb-0000-7000-8000-000000000001" }
    });

    await waitFor(() => {
      expect(api.listAssets).toHaveBeenCalledWith({
        locationId: "0190f8bb-0000-7000-8000-000000000001",
        assetType: undefined,
        status: undefined
      });
    });
  });

  it("displays empty state when no assets match", async () => {
    api.listAssets.mockResolvedValueOnce([]);
    renderPage();

    await waitFor(() => {
      expect(
        screen.getByText("No fixed assets found for the selected branch/filters.")
      ).toBeDefined();
    });
  });

  it("opens modal and registers a new asset when authorized", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Register Asset" })).toBeDefined();
    });

    fireEvent.click(screen.getByRole("button", { name: "Register Asset" }));

    expect(screen.getByRole("heading", { name: "Register Fixed Asset" })).toBeDefined();

    fireEvent.change(screen.getByLabelText(/Asset Code/i), { target: { value: "AST-003" } });
    fireEvent.change(screen.getByLabelText(/Asset Name/i), { target: { value: "Backup Generator" } });
    fireEvent.change(screen.getByLabelText(/Asset Type/i), { target: { value: "EQUIPMENT" } });

    fireEvent.click(screen.getByRole("button", { name: "Register" }));

    await waitFor(() => {
      expect(api.registerAsset).toHaveBeenCalledWith(
        expect.objectContaining({
          assetCode: "AST-003",
          name: "Backup Generator",
          assetType: "EQUIPMENT"
        }),
        expect.any(String)
      );
    });
  });

  it("hides registration button when user lacks rpt.asset.manage permission", async () => {
    mockCanManage = false;
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Asset Register & Branch View")).toBeDefined();
    });

    expect(screen.queryByRole("button", { name: "Register Asset" })).toBeNull();
  });
});
