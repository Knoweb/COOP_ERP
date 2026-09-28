import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Relationship } from "./partyApi";
import { RelationshipPage } from "./RelationshipPage";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const OLD = "0190f4dd-0000-7000-8000-000000000001";
const CURRENT = "0190f4dd-0000-7000-8000-000000000002";

const state = { entityId: SELLER, permissions: new Set<string>(["prt.relationship.amend"]) };

function row(id: string, from: string, to: string | null, limit: number): Relationship {
  return {
    relationshipId: id,
    sellerEntityId: SELLER,
    buyerEntityId: BUYER,
    creditLimit: limit,
    paymentTermsDays: 30,
    discrepancyWindowDays: 7,
    orderLockHoursBeforeEta: 24,
    allocationRule: "FCFS",
    status: "ACTIVE",
    effectiveFrom: from,
    effectiveTo: to
  };
}

const rows = [row(OLD, "2026-01-01", "2026-06-30", 3000000), row(CURRENT, "2026-07-01", null, 5000000)];

const api = {
  getRelationship: vi.fn(async (id: string) => rows.find((r) => r.relationshipId === id)!),
  listRelationships: vi.fn(async () => rows),
  getSociety: vi.fn(async () => {
    throw new Error("not needed");
  }),
  amendRelationship: vi.fn(async () => row("0190f4dd-0000-7000-8000-000000000003", "2026-10-01", null, 6000000))
};

vi.mock("./partyApi", () => ({ usePartyApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));
vi.mock("../../shell/scope/useScope", () => ({ useScope: () => ({ entityId: state.entityId }) }));

function renderAt(id: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/party/relationships/${id}`]}>
          <Routes>
            <Route path="/party/relationships/:relationshipId" element={<RelationshipPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the agreement sheet", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.entityId = SELLER;
    state.permissions = new Set(["prt.relationship.amend"]);
  });
  afterEach(cleanup);

  it("shows the pair's history and lets the seller change the credit limit with a reason", async () => {
    renderAt(CURRENT);
    expect(await screen.findByRole("heading", { name: text("party.relationship.history") })).toBeTruthy();
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(3));

    fireEvent.change(screen.getByLabelText(text("party.relationship.limit.new")), { target: { value: "6000000" } });
    fireEvent.change(screen.getByLabelText(text("party.relationship.limit.from")), { target: { value: "2099-10-01" } });
    fireEvent.click(screen.getByRole("button", { name: text("party.relationship.limit.change") }));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "REVIEW" } });
    fireEvent.click(screen.getByRole("button", { name: text("shell.reason.confirm") }));

    await waitFor(() => expect(api.amendRelationship).toHaveBeenCalledOnce());
    expect(api.amendRelationship.mock.calls[0]).toEqual([
      CURRENT,
      { effectiveFrom: "2099-10-01", creditLimit: 6000000, reasonCode: "REVIEW", reasonText: undefined },
      expect.any(String)
    ]);
  });

  it("offers no change on a closed row, nor to the buyer", async () => {
    renderAt(OLD);
    await screen.findByRole("heading", { name: text("party.relationship.history") });
    expect(screen.queryByRole("button", { name: text("party.relationship.limit.change") })).toBeNull();
    cleanup();

    state.entityId = BUYER;
    renderAt(CURRENT);
    await screen.findByRole("heading", { name: text("party.relationship.history") });
    expect(screen.queryByRole("button", { name: text("party.relationship.limit.change") })).toBeNull();
  });
});
