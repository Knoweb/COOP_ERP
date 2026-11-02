import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { SupplierResponse, SupplierImportReportResponse } from "./catalogueApi";
import { SuppliersPage } from "./SuppliersPage";

const OWNER = "0190f000-0000-7000-8000-000000000001";

const state = {
  entityId: OWNER,
  permissions: new Set<string>(["cat.supplier.manage"])
};

const mockSuppliers: SupplierResponse[] = [
  {
    supplierId: "0192e000-0000-7000-8000-000000000001",
    ownerEntityId: OWNER,
    name: "Ceylon Tea Board",
    supplierCode: "SUP-CTB",
    status: "ACTIVE"
  },
  {
    supplierId: "0192e000-0000-7000-8000-000000000002",
    ownerEntityId: OWNER,
    name: "Lanka Sugar Company",
    supplierCode: "SUP-LSC",
    status: "ACTIVE"
  }
];

const mockReport: SupplierImportReportResponse = {
  status: "VALIDATED",
  rows: 2,
  created: 2,
  skipped: 0,
  rejected: 0,
  errors: []
};

const api = {
  listSuppliers: vi.fn(async () => mockSuppliers),
  registerSupplier: vi.fn(async (body: { name: string }) => ({
    supplierId: "0192e000-0000-7000-8000-000000000003",
    ownerEntityId: OWNER,
    name: body.name,
    status: "ACTIVE" as const
  })),
  importSuppliers: vi.fn(async () => mockReport)
};

vi.mock("./catalogueApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./catalogueApi")>();
  return {
    ...actual,
    useCatalogueApi: () => api
  };
});

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderSuppliersPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/catalogue/suppliers"]}>
          <Routes>
            <Route path="/catalogue/suppliers" element={<SuppliersPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("SuppliersPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cat.supplier.manage"]);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders existing suppliers directory with code and status", async () => {
    renderSuppliersPage();

    expect(screen.getByText("Suppliers")).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByText("Ceylon Tea Board")).toBeTruthy();
      expect(screen.getByText("SUP-CTB")).toBeTruthy();
      expect(screen.getByText("Lanka Sugar Company")).toBeTruthy();
      expect(screen.getByText("SUP-LSC")).toBeTruthy();
    });
  });

  it("allows switching to bulk import tab when permission is present", async () => {
    renderSuppliersPage();

    const importTab = screen.getByRole("tab", { name: "Bulk Import" });
    expect(importTab).toBeTruthy();
    fireEvent.click(importTab);

    expect(screen.getByText("Bulk Import Suppliers")).toBeTruthy();
    expect(
      screen.getByText("Drop a supplier CSV here, or click to browse")
    ).toBeTruthy();
  });

  it("hides management features when user lacks cat.supplier.manage permission", async () => {
    state.permissions = new Set();
    renderSuppliersPage();

    await waitFor(() => {
      expect(screen.getByText("Ceylon Tea Board")).toBeTruthy();
    });

    expect(screen.queryByRole("tab", { name: "Bulk Import" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Register" })).toBeNull();
  });
});
