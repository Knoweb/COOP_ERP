import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { messages, type Locale } from "../../shell/i18n/messages";
import type { Location, Receipt, ReceiptFilter, TillPosition, TillSession } from "./posApi";
import { ReceiptPage } from "./ReceiptPage";
import { ReceiptsPage } from "./ReceiptsPage";
import { SessionsPage } from "./SessionsPage";

const SHOP = "0190f0de-0000-7000-8000-0000000000a1";
const RECEIPT = "0190f6aa-0000-7000-8000-000000000001";
const FLAGGED = "0190f6aa-0000-7000-8000-000000000002";
const SESSION = "0190f6bb-0000-7000-8000-000000000001";
const ORPHAN = "0190f6bb-0000-7000-8000-000000000002";
const POSITION = "0190f6cc-0000-7000-8000-000000000001";
const DAY = "2026-09-28";

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
  businessDate: DAY,
  netAmount: 1250,
  taxAmount: 0,
  grossAmount: 1250,
  flags: [],
  lines: [{ lineNo: 1, skuId: "s1", qty: 2, unitPrice: 625, lineTotal: 1250 }],
  tenders: [{ seq: 1, kind: "CASH", amount: 1250 }]
};
const flagged: Receipt = {
  ...receipt,
  documentId: FLAGGED,
  docNumberDisplay: "M101-S01-T1-RCT-0000008",
  grossAmount: 1300,
  flags: ["TOTAL_MISMATCH", "SOMETHING_NEW"]
};
const session: TillSession = {
  sessionId: SESSION,
  locationId: SHOP,
  tillPositionId: POSITION,
  businessDate: DAY,
  openedAt: "2026-09-28T02:30:00Z",
  floatAmount: 2000,
  status: "CLOSED",
  closedAt: "2026-09-28T12:30:00Z",
  countedCash: 3250,
  expectedCash: 3250,
  variance: 0
};
/** A close whose open never reached central (M6-10). */
const orphan: TillSession = {
  sessionId: ORPHAN,
  locationId: SHOP,
  status: "CLOSED",
  closedAt: "2026-09-28T13:00:00Z",
  countedCash: 4100,
  expectedCash: 4000,
  variance: 100
};
const positions: TillPosition[] = [{ tillPositionId: POSITION, locationId: SHOP, positionNo: 1, status: "ACTIVE", primary: true }];

const api = {
  locations: vi.fn(async () => [shop, warehouse]),
  positions: vi.fn(async () => positions),
  receipts: vi.fn(async (filter: ReceiptFilter, cursor?: string) =>
    filter.flaggedOnly
      ? { items: [flagged] }
      : cursor === undefined
        ? { items: [receipt], nextCursor: "page-2" }
        : { items: [flagged] }
  ),
  receipt: vi.fn(async (documentId: string) => {
    if (documentId === RECEIPT) {
      return receipt;
    }
    if (documentId === FLAGGED) {
      return flagged;
    }
    throw new ApiProblem({ status: 404, code: "not_found" });
  }),
  sessions: vi.fn(async () => ({ items: [orphan, session] })),
  session: vi.fn(async () => session),
  getSku: vi.fn(async () => ({ skuId: "s1", skuCode: "SKU-1", nameEn: "Samba rice 5 kg" }))
};

vi.mock("./posApi", () => ({ usePosApi: () => api }));

function renderAt(path: string, locale: Locale = "en") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale={locale} messages={messages[locale]}>
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

const text = (id: string, locale: Locale = "en") => (messages[locale] as Record<string, string>)[id];

describe("the shop's receipts", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("offers only shops, picks the one shop, and lists its receipts of the day with till, tender and total", async () => {
    renderAt(`/pos?day=${DAY}`);

    const link = await screen.findByRole("link", { name: "M101-S01-T1-RCT-0000007" }, { timeout: 10000 });
    expect(link.getAttribute("href")).toBe(`/pos/receipts/${RECEIPT}?location=${SHOP}`);
    expect(api.receipts).toHaveBeenCalledWith({ locationId: SHOP, businessDate: DAY, flaggedOnly: false }, undefined);
    expect(screen.queryByRole("option", { name: /W01/ })).toBeNull();
    const row = link.closest("tr")!;
    expect(within(row).getByText("Till 1")).toBeTruthy();
    expect(within(row).getByText("Cash")).toBeTruthy();
    expect(within(row).getByText("Rs 1,250.00")).toBeTruthy();
    expect(within(row).getByText(text("pos.receipt.issued"))).toBeTruthy();
  }, 15000);

  it("shows the next page on asking, with the flags in words and an unknown flag as sent", async () => {
    renderAt(`/pos?day=${DAY}`);

    fireEvent.click(await screen.findByRole("button", { name: text("pos.more") }, { timeout: 10000 }));

    const link = await screen.findByRole("link", { name: "M101-S01-T1-RCT-0000008" }, { timeout: 10000 });
    expect(api.receipts).toHaveBeenLastCalledWith({ locationId: SHOP, businessDate: DAY, flaggedOnly: false }, "page-2");
    const row = link.closest("tr")!;
    expect(within(row).getByText(text("pos.flag.TOTAL_MISMATCH"))).toBeTruthy();
    expect(within(row).getByText("SOMETHING_NEW")).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("pos.more") })).toBeNull();
  }, 15000);

  it("keeps the flagged receipts only when asked", async () => {
    renderAt(`/pos?day=${DAY}`);

    fireEvent.click(await screen.findByRole("checkbox", { name: text("pos.field.flagged_only") }, { timeout: 10000 }));

    expect(await screen.findByRole("link", { name: "M101-S01-T1-RCT-0000008" }, { timeout: 10000 })).toBeTruthy();
    expect(api.receipts).toHaveBeenLastCalledWith({ locationId: SHOP, businessDate: DAY, flaggedOnly: true }, undefined);
    expect(screen.queryByRole("link", { name: "M101-S01-T1-RCT-0000007" })).toBeNull();
  }, 15000);

  it.each<Locale>(["si", "ta"])("tells in %s what central flagged on one receipt", async (locale) => {
    renderAt(`/pos/receipts/${FLAGGED}?location=${SHOP}`, locale);

    expect(await screen.findByText(text("pos.flag.TOTAL_MISMATCH", locale), undefined, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByText(text("pos.receipt.flags.heading", locale))).toBeTruthy();
    expect(screen.getByText("SOMETHING_NEW")).toBeTruthy();
  }, 15000);

  it("shows one receipt, read on its own: lines, totals, tenders and its session", async () => {
    renderAt(`/pos/receipts/${RECEIPT}?location=${SHOP}`);

    expect(await screen.findByText("SKU-1 Samba rice 5 kg", undefined, { timeout: 10000 })).toBeTruthy();
    expect(api.receipt).toHaveBeenCalledWith(RECEIPT);
    expect(api.receipts).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: text("pos.receipt.title") })).toBeTruthy();
    expect(screen.getByText("M101-S01-T1-RCT-0000007")).toBeTruthy();
    expect(screen.getAllByText("Rs 1,250.00").length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText("Cash")).toBeTruthy();
    expect((await screen.findByRole("link", { name: /Closed at/ })).getAttribute("href")).toBe(
      `/pos/sessions?location=${SHOP}&day=${DAY}`
    );
    expect(screen.getByRole("link", { name: text("pos.back") }).getAttribute("href")).toBe(`/pos?location=${SHOP}`);
  }, 15000);

  it("says so when there is no such receipt", async () => {
    renderAt(`/pos/receipts/other?location=${SHOP}`);
    expect((await screen.findByRole("alert", undefined, { timeout: 10000 })).textContent).toBe(text("pos.error.not_found"));
  }, 15000);

  it("lists the till sessions with the float and the count at the close, and a close whose open never came", async () => {
    renderAt(`/pos/sessions?location=${SHOP}&day=${DAY}`);

    const cell = await screen.findByText("Rs 2,000.00", undefined, { timeout: 10000 });
    const row = cell.closest("tr")!;
    expect(within(row).getAllByText("Rs 3,250.00")).toHaveLength(2);
    expect(within(row).getByText(text("pos.session.status.CLOSED"))).toBeTruthy();
    expect(within(row).getByText("Till 1")).toBeTruthy();

    const orphanRow = screen.getByText(text("pos.session.open_missing")).closest("tr")!;
    expect(within(orphanRow).getByText("Rs 4,100.00")).toBeTruthy();
    expect(within(orphanRow).getByText("Rs 100.00")).toBeTruthy();
  }, 15000);
});
