import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { messages } from "../../shell/i18n/messages";
import type { Account, CustomerCard, PrivacyRequest } from "./customersApi";
import { CustomerCardPage } from "./CustomerCardPage";
import { PrivacyRequestsPage } from "./PrivacyRequestsPage";

const CUSTOMER_ID = "0190f7aa-0000-7000-8000-000000000002";
const ACCOUNT_ID = "0190f7bb-0000-7000-8000-000000000002";
const REQUEST_ID = "0190f7cc-0000-7000-8000-000000000002";

const state = { permissions: new Set<string>() };

const account: Account = {
  accountId: ACCOUNT_ID,
  accountNo: "A00002",
  customerId: CUSTOMER_ID,
  creditLimit: 10000,
  balance: 2500,
  available: 7500,
  offlineCap: 5000,
  termsDays: 30,
  hardBlock: false,
  status: "OPEN",
  openedAt: "2026-08-04T04:00:00Z",
  oldestUnpaid: "2026-08-05",
  unallocated: 0,
  ageing: { days0To30: 2500, days31To60: 0, days61To90: 0, over90: 0 }
};

function card(): CustomerCard {
  return {
    customerId: CUSTOMER_ID,
    displayName: "Sita Kumari",
    language: "si",
    phone: "+94700000103",
    status: "ACTIVE",
    registeredAt: "2026-08-04T04:00:00Z",
    registeredHere: true,
    attributes: {},
    tags: [],
    consents: [],
    phones: [],
    account
  };
}

const request: PrivacyRequest = {
  requestId: REQUEST_ID,
  customerId: CUSTOMER_ID,
  customerName: "Rizwan Hameed",
  kind: "ERASURE",
  receivedAt: "2026-09-26T05:30:00Z",
  receivedBy: "0190f0de-0000-7000-8000-000000000234",
  status: "RECEIVED"
};

const api = {
  customer: vi.fn(async () => card()),
  history: vi.fn(async () => [
    {
      historyId: "h1",
      action: "SUSPENDED",
      before: {},
      after: {},
      reason: "Repayments overdue",
      changedAt: "2026-09-28T11:30:00Z"
    }
  ]),
  adjustments: vi.fn(async () => [
    {
      adjustmentId: "a1",
      amount: -250,
      reason: "Damaged goods returned",
      status: "REQUESTED",
      requestedBy: "u1",
      requestedAt: "2026-09-28T05:30:00Z"
    }
  ]),
  amendLimits: vi.fn(async () => {
    throw new ApiProblem({ status: 401, code: "mfa.required", title: "Second factor needed" });
  }),
  changeStatus: vi.fn(async () => account),
  approveAdjustment: vi.fn(async () => ({})),
  requestAdjustment: vi.fn(async () => ({})),
  recordPrivacyRequest: vi.fn(async () => request),
  privacyRequests: vi.fn(async () => [request]),
  fulfilPrivacyRequest: vi.fn(async () => ({ ...request, status: "FULFILLED" })),
  refusePrivacyRequest: vi.fn(async () => ({ ...request, status: "REFUSED" })),
  privacyExport: vi.fn(async () => null)
};

vi.mock("./customersApi", () => ({ useCustomersApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: (code: string) => state.permissions.has(code) }));

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/customers/privacy" element={<PrivacyRequestsPage />} />
            <Route path="/customers/:customerId" element={<CustomerCardPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the account's limits, state and adjustments", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cus.customer.view", "cus.account.manage", "cus.account.adjust_approve"]);
  });
  afterEach(cleanup);

  it("warns that a higher limit needs the second factor and says so when the server asks", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    const limit = await screen.findByLabelText(text("customers.account.limit"), undefined, { timeout: 10000 });
    fireEvent.change(limit, { target: { value: "20000" } });
    expect(screen.getByText(text("customers.limits.step_up"))).toBeTruthy();
    const reasons = screen.getAllByLabelText(text("customers.reason"));
    fireEvent.change(reasons[0], { target: { value: "Pays on time" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.limits.submit") }));

    await waitFor(() => expect(api.amendLimits).toHaveBeenCalledOnce());
    const [, body] = api.amendLimits.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(body).toEqual({ creditLimit: 20000, reason: "Pays on time" });
    expect((await screen.findByRole("alert")).textContent).toBe(text("customers.limits.step_up_needed"));
  }, 15000);

  it("suspends with a reason, shows the history and approves another person's adjustment", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    const action = await screen.findByLabelText(text("customers.status_action.action"), undefined, { timeout: 10000 });
    expect(await screen.findByText(/Repayments overdue/)).toBeTruthy();
    fireEvent.change(action, { target: { value: "suspend" } });
    const reasons = screen.getAllByLabelText(text("customers.reason"));
    fireEvent.change(reasons[1], { target: { value: "Overdue" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.status_action.submit") }));
    await waitFor(() => expect(api.changeStatus).toHaveBeenCalledOnce());
    expect(api.changeStatus.mock.calls[0]).toEqual(expect.arrayContaining([ACCOUNT_ID, "suspend", "Overdue"]));

    fireEvent.click(screen.getByRole("button", { name: text("customers.adjustment.approve") }));
    await waitFor(() => expect(api.approveAdjustment).toHaveBeenCalledOnce());
    expect(api.approveAdjustment.mock.calls[0]).toEqual(expect.arrayContaining([ACCOUNT_ID, "a1"]));
  }, 15000);
});

describe("the privacy requests", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cus.customer.view", "cus.privacy.record", "cus.privacy.fulfil"]);
  });
  afterEach(cleanup);

  it("records a request from the customer card", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    const kind = await screen.findByLabelText(text("customers.privacy.record.kind"), undefined, { timeout: 10000 });
    fireEvent.change(kind, { target: { value: "ACCESS" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.privacy.record.submit") }));
    await waitFor(() => expect(api.recordPrivacyRequest).toHaveBeenCalledOnce());
    const [body] = api.recordPrivacyRequest.mock.calls[0] as unknown as [Record<string, unknown>];
    expect(body).toMatchObject({ customerId: CUSTOMER_ID, kind: "ACCESS" });
  }, 15000);

  it("lets the officer fulfil an erasure after the warning, or refuse it on a ground", async () => {
    renderAt("/customers/privacy");
    expect(await screen.findByText(text("customers.privacy.erasure_warning"), undefined, { timeout: 10000 })).toBeTruthy();
    const refuse = screen.getByRole("button", { name: text("customers.privacy.refuse") }) as HTMLButtonElement;
    expect(refuse.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(text("customers.privacy.answer")), {
      target: { value: "Accounting records kept seven years" }
    });
    fireEvent.click(screen.getByRole("button", { name: text("customers.privacy.refuse") }));
    await waitFor(() => expect(api.refusePrivacyRequest).toHaveBeenCalledOnce());
    expect(api.refusePrivacyRequest.mock.calls[0]).toEqual(
      expect.arrayContaining([REQUEST_ID, "Accounting records kept seven years"])
    );
  }, 15000);
});
