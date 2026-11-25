import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { DeliveryNote, Exposure } from "./tradingApi";
import { DeliveryNotePage } from "./DeliveryNotePage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const NOTE_ID = "0190f4ef-0000-7000-8000-000000000001";
const ORDER_ID = "0190f4cc-0000-7000-8000-000000000001";
const WAREHOUSE = "0190f0de-0000-7000-8000-000000000111";

const state = {
  entityId: SELLER,
  permissions: new Set<string>(["del.note.issue", "del.note.dispatch"])
};

let currentNote: DeliveryNote;
let currentExposures: Exposure[];

const api = {
  deliveryNote: vi.fn(async () => currentNote),
  order: vi.fn(async () => ({
    orderId: ORDER_ID,
    deliverToLocationId: WAREHOUSE,
    deliverTo: { code: "W01", nameEn: "Kurunegala warehouse" }
  })),
  exposures: vi.fn(async () => currentExposures),
  issueDeliveryNote: vi.fn(async () => currentNote),
  dispatchDeliveryNote: vi.fn(async () => currentNote),
  entity: vi.fn(async () => null),
  location: vi.fn(async () => null),
  sku: vi.fn(async () => null)
};

vi.mock("./tradingApi", () => ({ useTradingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));

function renderDeliveryNote() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/trading/delivery-notes/${NOTE_ID}`]}>
          <Routes>
            <Route path="/trading/delivery-notes/:deliveryNoteId" element={<DeliveryNotePage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the delivery note page exposure warning banner (MNY-05)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.entityId = SELLER;
    state.permissions = new Set(["del.note.issue", "del.note.dispatch"]);

    currentNote = {
      deliveryNoteId: NOTE_ID,
      docNumber: "D101-DN-0000001",
      status: "DRAFT",
      sellerEntityId: SELLER,
      buyerEntityId: BUYER,
      fromLocationId: WAREHOUSE,
      vehicleRef: "WP-CAB-1234",
      driverName: "Kamal Perera",
      drops: []
    };

    currentExposures = [
      {
        relationshipId: "0190f4dd-0000-7000-8000-000000000001",
        sellerEntityId: SELLER,
        buyerEntityId: BUYER,
        creditLimit: 50000,
        amount: 65000,
        openInvoices: 40000,
        acceptedNotInvoiced: 25000,
        unappliedReceipts: 0,
        asOf: "2026-10-19T10:30:00Z"
      }
    ];
  });

  afterEach(() => {
    cleanup();
  });

  it("shows the warning banner with freshness timestamp when buyer exposure exceeds credit limit", async () => {
    renderDeliveryNote();
    const banner = await screen.findByTestId("exposure-warning-banner", {}, { timeout: 10000 });
    expect(banner).toBeTruthy();

    expect(banner.textContent).toContain(text("trading.note.exposure_warning"));
    expect(banner.textContent).toContain("65,000.00");
    expect(banner.textContent).toContain("50,000.00");
    // Freshness timestamp is formatted and present
    expect(banner.textContent).toContain("Data as of");
  }, 10000);

  it("does not block delivery: Issue button remains enabled when banner is shown", async () => {
    renderDeliveryNote();
    await screen.findByTestId("exposure-warning-banner", {}, { timeout: 10000 });

    const issueButton = screen.getByRole("button", { name: text("trading.note.issue") }) as HTMLButtonElement;
    expect(issueButton.disabled).toBe(false);
  }, 10000);

  it("does not block delivery: Dispatch button remains enabled on ISSUED note when banner is shown", async () => {
    currentNote.status = "ISSUED";
    renderDeliveryNote();
    await screen.findByTestId("exposure-warning-banner", {}, { timeout: 10000 });

    const dispatchButton = screen.getByRole("button", { name: text("trading.note.dispatch") }) as HTMLButtonElement;
    expect(dispatchButton.disabled).toBe(false);
  }, 10000);

  it("does not show the banner when buyer exposure is within the credit limit", async () => {
    currentExposures[0].amount = 40000;
    renderDeliveryNote();
    await screen.findByText("D101-DN-0000001", {}, { timeout: 10000 });
    await waitFor(() => expect(api.exposures).toHaveBeenCalled(), { timeout: 10000 });

    expect(screen.queryByTestId("exposure-warning-banner")).toBeNull();
  }, 10000);

  it("does not show the banner when buyer has no credit limit set", async () => {
    currentExposures[0].creditLimit = undefined;
    renderDeliveryNote();
    await screen.findByText("D101-DN-0000001", {}, { timeout: 10000 });
    await waitFor(() => expect(api.exposures).toHaveBeenCalled(), { timeout: 10000 });

    expect(screen.queryByTestId("exposure-warning-banner")).toBeNull();
  }, 10000);

  it("does not show the banner when viewed by the buyer", async () => {
    state.entityId = BUYER;
    renderDeliveryNote();
    await screen.findByText("D101-DN-0000001", {}, { timeout: 10000 });

    expect(screen.queryByTestId("exposure-warning-banner")).toBeNull();
  }, 10000);
});
