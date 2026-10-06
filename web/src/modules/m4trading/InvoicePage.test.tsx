import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Invoice } from "./tradingApi";
import { InvoicePage } from "./InvoicePage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const INVOICE_ID = "0190f4ee-0000-7000-8000-000000000001";
const GRN_ID = "0190f4ff-0000-7000-8000-000000000001";
const NOTE_ID = "0190f4ab-0000-7000-8000-000000000001";

const state = { entityId: SELLER, permissions: new Set<string>() };
const CREDIT_NOTE_ID = "0190f4cc-0000-7000-8000-000000000001";
let invoice: Invoice;
const plainInvoice: Invoice = {
  invoiceId: INVOICE_ID,
  docNumber: "FED-INV-0000001",
  status: "ISSUED",
  sellerEntityId: SELLER,
  buyerEntityId: BUYER,
  sellerVatNo: "VAT-FED-DEV",
  grnIds: [GRN_ID],
  taxPointDate: "2026-09-27",
  dueDate: "2026-10-27",
  netAmount: 145320,
  taxAmount: 0,
  grossAmount: 145320,
  lines: [
    { lineId: "l1", lineNo: 1, skuId: "s1", uomCode: "EA", qty: 120, unitPrice: 1120, taxRatePercent: 0, taxAmount: 0, lineTotal: 134400 }
  ]
};

const api = {
  invoice: vi.fn(async (): Promise<Invoice> => invoice),
  grn: vi.fn(async () => ({ grnId: GRN_ID, docNumber: "D101-GRN-0000001", deliveryNoteId: NOTE_ID })),
  invoicePrint: vi.fn(async () => `${window.location.origin}/reports/x.pdf`),
  sku: vi.fn(async () => null),
  entity: vi.fn(async () => null),
  applyCreditNote: vi.fn(async () => ({}))
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));

function renderInvoice() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/trading/invoices/${INVOICE_ID}`]}>
          <Routes>
            <Route path="/trading/invoices/:invoiceId" element={<InvoicePage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the invoice", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    invoice = plainInvoice;
    state.permissions = new Set();
  });
  afterEach(cleanup);

  it("offers the seller's accounts a credit note of the buyer that still holds money (CR-24A-3 item 2)", async () => {
    state.entityId = SELLER;
    state.permissions = new Set(["bil.creditnote.issue"]);
    invoice = {
      ...plainInvoice,
      amountDue: 145320,
      availableCredits: [{ creditNoteId: CREDIT_NOTE_ID, docNumber: "FED-CN-0000001", grossAmount: 283.2, appliedAmount: 0, unappliedAmount: 283.2 }]
    };
    renderInvoice();

    expect((await screen.findByRole("link", { name: "FED-CN-0000001" }, { timeout: 10000 })).getAttribute("href")).toBe(
      `/trading/credit-notes/${CREDIT_NOTE_ID}`
    );
    fireEvent.click(screen.getByRole("button", { name: text("trading.invoice.apply_credit") }));

    await waitFor(() => expect(api.applyCreditNote).toHaveBeenCalledOnce());
    expect(api.applyCreditNote).toHaveBeenCalledWith(CREDIT_NOTE_ID, INVOICE_ID, expect.any(String));
  }, 15000);

  it("offers the buyer nothing to apply", async () => {
    state.entityId = BUYER;
    state.permissions = new Set(["bil.creditnote.issue"]);
    invoice = {
      ...plainInvoice,
      amountDue: 145320,
      availableCredits: [{ creditNoteId: CREDIT_NOTE_ID, docNumber: "FED-CN-0000001", grossAmount: 283.2, unappliedAmount: 283.2 }]
    };
    renderInvoice();

    await screen.findByRole("button", { name: text("trading.invoice.print") }, { timeout: 10000 });
    expect(screen.queryByRole("button", { name: text("trading.invoice.apply_credit") })).toBeNull();
  }, 15000);

  it("links to the GRN and the delivery note, and the seller's Print opens the A4 PDF", async () => {
    state.entityId = SELLER;
    const tab = { location: { href: "" }, close: vi.fn() };
    const open = vi.spyOn(window, "open").mockReturnValue(tab as unknown as Window);
    renderInvoice();

    expect((await screen.findByRole("link", { name: "D101-GRN-0000001" }, { timeout: 10000 })).getAttribute("href")).toBe(`/trading/grns/${GRN_ID}`);
    expect(screen.getByRole("link", { name: text("trading.note.open") }).getAttribute("href")).toBe(`/trading/delivery-notes/${NOTE_ID}`);
    fireEvent.click(screen.getByRole("button", { name: text("trading.invoice.print") }));

    await waitFor(() => expect(tab.location.href).toBe(`${window.location.origin}/reports/x.pdf`));
    expect(open).toHaveBeenCalledOnce();
    open.mockRestore();
  }, 15000);

  it("gives the buyer Print too: the same A4 PDF, reached through the invoice", async () => {
    state.entityId = BUYER;
    const tab = { location: { href: "" }, close: vi.fn() };
    const open = vi.spyOn(window, "open").mockReturnValue(tab as unknown as Window);
    renderInvoice();

    fireEvent.click(await screen.findByRole("button", { name: text("trading.invoice.print") }, { timeout: 10000 }));

    await waitFor(() => expect(tab.location.href).toBe(`${window.location.origin}/reports/x.pdf`));
    expect(open).toHaveBeenCalledOnce();
    open.mockRestore();
  }, 15000);
});
