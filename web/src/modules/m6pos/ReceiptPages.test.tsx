import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Location, Receipt, TillPosition, TillSession } from "./posApi";
import { ReceiptPage } from "./ReceiptPage";
import { ReceiptsPage } from "./ReceiptsPage";
import { SessionsPage } from "./SessionsPage";

const SHOP = "0190f0de-0000-7000-8000-0000000000a1";
const RECEIPT = "0190f6aa-0000-7000-8000-000000000001";
const SESSION = "0190f6bb-0000-7000-8000-000000000001";
const POSITION = "0190f6cc-0000-7000-8000-000000000001";

const shop: Location = {
  locationId: SHOP,
  locationCode: "S01",
  nameEn: "Kuliyapitiya town shop",
  locationType: "SHOP"
} as Location;
const warehouse = { ...shop, locationId: "w1", locationCode: "W01", nameEn: "Warehouse", locationType: "WAREHOUSE" } as Location;

const receipt: Receipt = {
  documentId: RECEIPT,
  locationId: SHOP,
  sessionId: SESSION,
  tillPositionId: POSITION,
  docNumberDisplay: "M101-S01-T1-RCT-0000007",
  issuedAt: "2026-09-28T04:30:00Z",
  businessDate: "2026-09-28",
  netAmount: 1250,
  taxAmount: 0,
  grossAmount: 1250,
  flags: [],
  lines: [{ lineNo: 1, skuId: "s1", qty: 2, unitPrice: 625, lineTotal: 1250 }],
  tenders: [{ seq: 1, kind: "CASH", amount: 1250 }]
};
const session: TillSession = {
  sessionId: SESSION,
  locationId: SHOP,
  tillPositionId: POSITION,
  businessDate: "2026-09-28",
  openedAt: "2026-09-28T02:30:00Z",
  floatAmount: 2000,
  status: "CLOSED",
  closedAt: "2026-09-28T12:30:00Z",
  countedCash: 3250,
  expectedCash: 3250,
  variance: 0
};
const positions: TillPosition[] = [{ tillPositionId: POSITION, locationId: SHOP, positionNo: 1, status: "ACTIVE", primary: true }];

const api = {
  locations: vi.fn(async () => [shop, warehouse]),
  positions: vi.fn(async () => positions),
  receipts: vi.fn(async () => [receipt]),
  sessions: vi.fn(async () => [session]),
  getSku: vi.fn(async () => ({ skuId: "s1", skuCode: "SKU-1", nameEn: "Samba rice 5 kg" }))
};

vi.mock("./posApi", () => ({ usePosApi: () => api }));

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/pos" element={<ReceiptsPage />} />
            <Route path="/pos/receipts/:documentId" element={<ReceiptPage />} />
            <Route path="/pos/sessions" element={<SessionsPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the shop's receipts", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("offers only shops, picks the one shop, and lists its receipts with till, tender and total", async () => {
    renderAt("/pos");

    const link = await screen.findByRole("link", { name: "M101-S01-T1-RCT-0000007" }, { timeout: 10000 });
    expect(link.getAttribute("href")).toBe(`/pos/receipts/${RECEIPT}?location=${SHOP}`);
    expect(api.receipts).toHaveBeenCalledWith(SHOP);
    expect(screen.queryByRole("option", { name: /W01/ })).toBeNull();
    const row = link.closest("tr")!;
    expect(within(row).getByText("Till 1")).toBeTruthy();
    expect(within(row).getByText("Cash")).toBeTruthy();
    expect(within(row).getByText("Rs 1,250.00")).toBeTruthy();
    expect(within(row).getByText(text("pos.receipt.issued"))).toBeTruthy();
  }, 15000);

  it("shows one receipt: lines, totals, tenders and its session", async () => {
    renderAt(`/pos/receipts/${RECEIPT}?location=${SHOP}`);

    expect(await screen.findByText("SKU-1 Samba rice 5 kg", undefined, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByRole("heading", { name: text("pos.receipt.title") })).toBeTruthy();
    expect(screen.getByText("M101-S01-T1-RCT-0000007")).toBeTruthy();
    expect(screen.getAllByText("Rs 1,250.00").length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText("Cash")).toBeTruthy();
    expect(screen.getByRole("link", { name: /Closed at/ }).getAttribute("href")).toBe(`/pos/sessions?location=${SHOP}`);
    expect(screen.getByRole("link", { name: text("pos.back") }).getAttribute("href")).toBe(`/pos?location=${SHOP}`);
  }, 15000);

  it("says so when the receipt is not in the shop's list", async () => {
    renderAt(`/pos/receipts/other?location=${SHOP}`);
    expect((await screen.findByRole("alert", undefined, { timeout: 10000 })).textContent).toBe(text("pos.error.not_found"));
  }, 15000);

  it("lists the till sessions with the float and the count at the close", async () => {
    renderAt(`/pos/sessions?location=${SHOP}`);

    const cell = await screen.findByText("Rs 2,000.00", undefined, { timeout: 10000 });
    const row = cell.closest("tr")!;
    expect(within(row).getAllByText("Rs 3,250.00")).toHaveLength(2);
    expect(within(row).getByText(text("pos.session.status.CLOSED"))).toBeTruthy();
    expect(within(row).getByText("Till 1")).toBeTruthy();
  }, 15000);
});
