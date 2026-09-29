import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Claim } from "./tradingApi";
import { ClaimPage } from "./ClaimPage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const CLAIM_ID = "0190f5aa-0000-7000-8000-000000000001";
const LINE_ID = "0190f5ab-0000-7000-8000-000000000001";
const CN_ID = "0190f4cc-0000-7000-8000-000000000001";

const state = {
  entityId: SELLER,
  permissions: new Set<string>(["del.claim.decide"]),
  status: "RAISED" as Claim["status"],
  photo: "COMPLETE" as "PENDING" | "COMPLETE" | "FAILED",
  returnRequired: false,
  returned: false
};

function claim(): Claim {
  return {
    claimId: CLAIM_ID,
    docNumber: "D101-CLM-0000001",
    status: state.status,
    kind: "DAMAGED",
    buyerEntityId: BUYER,
    sellerEntityId: SELLER,
    grnId: "0190f4ff-0000-7000-8000-000000000001",
    grnDocNumber: "D101-GRN-0000001",
    windowEndsAt: "2026-10-10T00:00:00Z",
    invoiceId: "0190f4ee-0000-7000-8000-000000000001",
    returnRequested: true,
    returnRequired: state.returnRequired,
    creditNoteId: state.status === "APPROVED" ? CN_ID : undefined,
    creditNoteDocNumber: state.status === "APPROVED" ? "FED-CN-0000001" : undefined,
    returnedAt: state.returned ? "2026-09-29T09:00:00Z" : undefined,
    photos: [{ attachmentId: "0190f5ac-0000-7000-8000-000000000001", status: state.photo }],
    lines: [
      {
        claimLineId: LINE_ID,
        grnLineId: "g1",
        skuId: "s1",
        uomCode: "EA",
        claimedQty: 3,
        approvedQty: state.status === "APPROVED" ? 2 : undefined,
        unitPrice: 120
      }
    ]
  };
}

const api = {
  claim: vi.fn(async () => claim()),
  approveClaim: vi.fn(async () => {
    state.status = "APPROVED";
    return claim();
  }),
  rejectClaim: vi.fn(async () => {
    state.status = "REJECTED";
    return claim();
  }),
  dispatchClaimReturn: vi.fn(async () => {
    state.returned = true;
    return claim();
  }),
  addClaimPhoto: vi.fn(async () => undefined),
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
        <MemoryRouter initialEntries={[`/trading/claims/${CLAIM_ID}`]}>
          <Routes>
            <Route path="/trading/claims/:claimId" element={<ClaimPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the claim", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.entityId = SELLER;
    state.permissions = new Set(["del.claim.decide"]);
    state.status = "RAISED";
    state.photo = "COMPLETE";
    state.returnRequired = false;
    state.returned = false;
  });
  afterEach(cleanup);

  it("lets the seller approve part of it with the goods to come back", async () => {
    renderPage();

    const accepted = await screen.findByRole("textbox", { name: text("trading.claim.accepted") }, { timeout: 10000 });
    fireEvent.change(accepted, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("checkbox", { name: text("trading.claim.return_required") }));
    fireEvent.click(screen.getByRole("button", { name: text("trading.claim.approve") }));

    await waitFor(() =>
      expect(api.approveClaim).toHaveBeenCalledWith(
        CLAIM_ID,
        { findings: undefined, returnRequired: true, lines: [{ claimLineId: LINE_ID, qty: 2 }] },
        expect.any(String)
      )
    );
    expect(await screen.findByRole("link", { name: "FED-CN-0000001" }, { timeout: 10000 })).toBeTruthy();
  }, 15000);

  it("waits for every photograph before the seller can decide", async () => {
    state.photo = "PENDING";
    renderPage();

    expect(await screen.findByText(text("trading.claim.evidence_pending"), {}, { timeout: 10000 })).toBeTruthy();
    const approve = screen.getByRole("button", { name: text("trading.claim.approve") }) as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
  }, 15000);

  it("refuses an accepted quantity above the claim", async () => {
    renderPage();

    const accepted = await screen.findByRole("textbox", { name: text("trading.claim.accepted") }, { timeout: 10000 });
    fireEvent.change(accepted, { target: { value: "5" } });

    expect(screen.getByText(text("trading.claim.accepted_invalid"))).toBeTruthy();
    const approve = screen.getByRole("button", { name: text("trading.claim.approve") }) as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
  }, 15000);

  it("lets the buyer send back the goods the seller asked for, and nothing else", async () => {
    state.entityId = BUYER;
    state.permissions = new Set(["del.claim.raise"]);
    state.status = "APPROVED";
    state.returnRequired = true;
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: text("trading.claim.return") }, { timeout: 10000 }));

    await waitFor(() => expect(api.dispatchClaimReturn).toHaveBeenCalledWith(CLAIM_ID, expect.any(String)));
    expect(screen.queryByRole("button", { name: text("trading.claim.approve") })).toBeNull();
  }, 15000);
});
