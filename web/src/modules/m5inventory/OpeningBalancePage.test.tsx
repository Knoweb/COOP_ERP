import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { OpeningBalance } from "./inventoryApi";
import { OpeningBalancePage } from "./OpeningBalancePage";

const OPB_ID = "0190f6ac-0000-7000-8000-000000000010";
const SHOP = "0190f0de-0000-7000-8000-000000000132";
const SKU_1 = "0190f6ab-0000-7000-8000-000000000001";

const mockDraftBalance: OpeningBalance = {
  openingBalanceId: OPB_ID,
  locationId: SHOP,
  status: "DRAFT",
  lines: [
    {
      lineNo: 1,
      skuId: SKU_1,
      qty: 20,
      unitCost: 150,
      condition: "GOOD"
    }
  ]
};

const mockRejectedBalance: OpeningBalance = {
  ...mockDraftBalance,
  status: "REJECTED",
  rejectionReason: "Significant discrepancy found during recount"
};

const permissions = new Set<string>(["inv.opening.sign", "inv.opening.countersign", "inv.opening.prepare"]);

const api = {
  openingBalance: vi.fn(async () => mockDraftBalance),
  sign: vi.fn(async () => mockDraftBalance),
  countersign: vi.fn(async () => mockDraftBalance),
  rejectOpeningBalance: vi.fn(async () => mockRejectedBalance)
};

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => permissions.has(code)
}));
vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));
vi.mock("./SkuLabel", () => ({ SkuLabel: ({ skuId }: { skuId: string }) => <span>SKU-{skuId}</span> }));
vi.mock("../../shell/api/idempotency", () => ({
  useIdempotencyKey: () => ({ current: () => "0190f6ab-0000-7000-8000-000000000001", next: vi.fn() })
}));

function renderPage(initialEntry = `/inventory/opening/${OPB_ID}`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path="/inventory/opening/:openingBalanceId" element={<OpeningBalancePage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("OpeningBalancePage (PGL-M2-06)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions.clear();
    permissions.add("inv.opening.sign");
    permissions.add("inv.opening.countersign");
    permissions.add("inv.opening.prepare");
  });
  afterEach(cleanup);

  it("renders draft opening balance with lines and action buttons", async () => {
    api.openingBalance.mockResolvedValueOnce(mockDraftBalance);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(text("inventory.opening.title"))).toBeDefined();
      expect(screen.getByText(text("inventory.opening.status.DRAFT"))).toBeDefined();
      expect(screen.getByText(text("inventory.opening.sign"))).toBeDefined();
      expect(screen.getByText(text("inventory.opening.reject"))).toBeDefined();
    });
  });

  it("hides reject button when user only has inv.opening.prepare without signing authority", async () => {
    permissions.clear();
    permissions.add("inv.opening.prepare");
    api.openingBalance.mockResolvedValueOnce(mockDraftBalance);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(text("inventory.opening.title"))).toBeDefined();
    });
    expect(screen.queryByText(text("inventory.opening.reject"))).toBeNull();
  });

  it("allows higher officer with sign permission to reject draft with reason", async () => {
    api.openingBalance.mockResolvedValueOnce(mockDraftBalance);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(text("inventory.opening.reject"))).toBeDefined();
    });

    // Click reject draft button
    fireEvent.click(screen.getByText(text("inventory.opening.reject")));

    // Rejection reason input should be visible
    const reasonInput = screen.getByPlaceholderText(text("inventory.opening.reject.placeholder"));
    expect(reasonInput).toBeDefined();

    const confirmBtn = screen.getByText(text("inventory.opening.reject.confirm"));
    expect((confirmBtn as HTMLButtonElement).disabled).toBe(true);

    // Type valid reason
    fireEvent.change(reasonInput, { target: { value: "Physical count was missing 5 packets of dhal" } });
    expect((confirmBtn as HTMLButtonElement).disabled).toBe(false);

    // Click confirm
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(api.rejectOpeningBalance).toHaveBeenCalledWith(
        OPB_ID,
        "Physical count was missing 5 packets of dhal",
        expect.any(String)
      );
    });
  });

  it("renders rejection banner when opening balance is REJECTED", async () => {
    api.openingBalance.mockResolvedValueOnce(mockRejectedBalance);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(text("inventory.opening.status.REJECTED"))).toBeDefined();
      expect(screen.getByRole("alert")).toBeDefined();
      expect(screen.getByText(/Significant discrepancy found during recount/)).toBeDefined();
    });
  });
});
