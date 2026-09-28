import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Discrepancy } from "./tradingApi";
import { DiscrepancyPage } from "./DiscrepancyPage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const DISC_ID = "0190f4dd-0000-7000-8000-000000000001";
const GRN_ID = "0190f4ff-0000-7000-8000-000000000001";
const INVOICE_ID = "0190f4ee-0000-7000-8000-000000000001";
const CN_ID = "0190f4cc-0000-7000-8000-000000000001";

const state = { entityId: SELLER, permissions: new Set<string>(["bil.creditnote.issue"]), settled: false };

function discrepancy(): Discrepancy {
  return {
    discrepancyId: DISC_ID,
    docNumber: "D101-DISC-0000001",
    status: state.settled ? "SETTLED" : "RAISED",
    kind: "SHORT",
    buyerEntityId: BUYER,
    sellerEntityId: SELLER,
    grnId: GRN_ID,
    grnDocNumber: "D101-GRN-0000001",
    windowEndsAt: "2026-10-05T00:00:00Z",
    invoiceId: INVOICE_ID,
    creditNoteId: state.settled ? CN_ID : undefined,
    creditNoteDocNumber: state.settled ? "FED-CN-0000001" : undefined,
    lines: [
      { lineId: "l1", grnLineId: "g1", skuId: "s1", uomCode: "EA", expectedQty: 40, receivedQty: 38, damagedQty: 0, varianceQty: -2, unitPrice: 310 }
    ]
  };
}

const api = {
  discrepancy: vi.fn(async () => discrepancy()),
  settleDiscrepancy: vi.fn(async () => ({ creditNoteId: CN_ID })),
  sku: vi.fn(async () => null),
  entity: vi.fn(async () => null)
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/trading/discrepancies/${DISC_ID}`]}>
          <Routes>
            <Route path="/trading/discrepancies/:discrepancyId" element={<DiscrepancyPage />} />
            <Route path="/trading/credit-notes/:creditNoteId" element={<p>credit note page</p>} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the discrepancy", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.settled = false;
  });
  afterEach(cleanup);

  it("lets the seller's accounts settle it with a credit note against the invoice", async () => {
    state.entityId = SELLER;
    state.permissions = new Set(["bil.creditnote.issue"]);
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: text("trading.discrepancy.settle") }, { timeout: 10000 }));

    await screen.findByText("credit note page");
    expect(api.settleDiscrepancy).toHaveBeenCalledWith(
      INVOICE_ID,
      DISC_ID,
      text("trading.discrepancy.reason_default"),
      expect.any(String)
    );
  }, 15000);

  it("shows the buyer the open discrepancy read only", async () => {
    state.entityId = BUYER;
    state.permissions = new Set(["bil.invoice.dispute"]);
    renderPage();

    expect(await screen.findByText(text("trading.discrepancy.status.RAISED"), {}, { timeout: 10000 })).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("trading.discrepancy.settle") })).toBeNull();
  }, 15000);

  it("leads both parties to the credit note once settled", async () => {
    state.settled = true;
    state.entityId = BUYER;
    renderPage();

    const link = await screen.findByRole("link", { name: "FED-CN-0000001" }, { timeout: 10000 });
    expect(link.getAttribute("href")).toBe(`/trading/credit-notes/${CN_ID}`);
    await waitFor(() => expect(screen.queryByRole("button", { name: text("trading.discrepancy.settle") })).toBeNull());
  }, 15000);
});
