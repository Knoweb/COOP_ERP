import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Exposure, PaymentReceipt } from "./tradingApi";
import { ExposurePanel } from "./ExposurePanel";
import { PaymentEntry } from "./PaymentEntry";
import { PaymentPage } from "./PaymentPage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const RECEIPT_ID = "0190f4aa-0000-7000-8000-000000000001";
const REVERSAL_ID = "0190f4aa-0000-7000-8000-000000000002";
const INVOICE_ID = "0190f4ee-0000-7000-8000-000000000001";

const state = { entityId: SELLER, permissions: new Set<string>(["bil.payment.record"]), bounced: false };

function receipt(): PaymentReceipt {
  return {
    receiptId: RECEIPT_ID,
    docNumber: "FED-PRC-0000001",
    status: state.bounced ? "REVERSED" : "RECORDED",
    sellerEntityId: SELLER,
    buyerEntityId: BUYER,
    method: "CHEQUE",
    receivedOn: "2026-09-28",
    amount: 1736,
    unappliedAmount: 0,
    reversedBy: state.bounced ? REVERSAL_ID : undefined,
    cheque: { bank: "Bank of Ceylon", chequeNo: "400123", dated: "2026-09-28", outcome: state.bounced ? "BOUNCED" : undefined },
    allocations: [{ invoiceId: INVOICE_ID, invoiceNumber: "FED-INV-0000009", amount: 1736 }]
  };
}

const api = {
  payment: vi.fn(async () => receipt()),
  chequeOutcome: vi.fn(async () => {
    state.bounced = true;
    return receipt();
  }),
  recordPayment: vi.fn(async () => receipt()),
  entity: vi.fn(async () => null)
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));

function renderWith(element: React.ReactElement, path = `/trading/payments/${RECEIPT_ID}`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/trading/payments/:receiptId" element={element} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the payment receipt", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.entityId = SELLER;
    state.bounced = false;
    state.permissions = new Set(["bil.payment.record"]);
  });
  afterEach(cleanup);

  it("lets the seller's accounts record that the cheque bounced, and then shows the reversal", async () => {
    renderWith(<PaymentPage />);

    expect(await screen.findByRole("link", { name: "FED-INV-0000009" }, { timeout: 10000 })).toBeTruthy();
    fireEvent.change(screen.getByLabelText(text("trading.payment.bounce_reason")), { target: { value: "Refer to drawer" } });
    fireEvent.click(screen.getByRole("button", { name: text("trading.payment.bounced") }));

    await waitFor(() => expect(api.chequeOutcome).toHaveBeenCalledWith(RECEIPT_ID, "BOUNCED", "Refer to drawer", expect.any(String)));
    expect(await screen.findByText(text("trading.payment.status.REVERSED"))).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("trading.payment.bounced") })).toBeNull();
  }, 15000);

  it("is read only for the buyer", async () => {
    state.entityId = BUYER;
    state.permissions = new Set();
    renderWith(<PaymentPage />);

    expect(await screen.findByRole("link", { name: "FED-INV-0000009" }, { timeout: 10000 })).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("trading.payment.bounced") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("trading.payment.cleared") })).toBeNull();
  }, 15000);

  it("records a payment against an invoice, starting at its amount due", async () => {
    renderWith(<PaymentEntry buyerEntityId={BUYER} invoiceId={INVOICE_ID} amountDue={1736} />);

    const record = await screen.findByRole("button", { name: text("trading.payment.record") });
    fireEvent.change(screen.getByLabelText(text("trading.payment.reference")), { target: { value: "TT-1001" } });
    fireEvent.click(record);

    await waitFor(() => expect(api.recordPayment).toHaveBeenCalledOnce());
    const [body] = api.recordPayment.mock.calls[0] as unknown as [Record<string, unknown>];
    expect(body).toMatchObject({
      buyerEntityId: BUYER,
      method: "TRANSFER",
      amount: 1736,
      reference: "TT-1001",
      settlements: [{ invoiceId: INVOICE_ID, amount: 1736 }]
    });
  }, 15000);
});

describe("the exposure panel", () => {
  afterEach(cleanup);

  it("warns past a threshold and says what accepting would take it to, without blocking", () => {
    const exposure: Exposure = {
      relationshipId: "r1",
      sellerEntityId: SELLER,
      buyerEntityId: BUYER,
      creditLimit: 27000,
      openInvoices: 20000,
      acceptedNotInvoiced: 4517.88,
      unappliedReceipts: 0,
      amount: 24517.88,
      warnThresholdPercent: 80,
      asOf: "2026-09-28T10:00:00Z"
    };
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <ExposurePanel exposure={exposure} orderValue={5000} />
      </IntlProvider>
    );

    expect(screen.getByText(text("trading.exposure.warning").replace("{percent}", "80"))).toBeTruthy();
    expect(screen.getByText(text("trading.exposure.over_limit").trim())).toBeTruthy();
  });
});
