import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { messages } from "../../shell/i18n/messages";
import type { Account, CustomerCard, CustomerSummary } from "./customersApi";
import { CustomerCardPage } from "./CustomerCardPage";
import { CustomersPage } from "./CustomersPage";

const CUSTOMER_ID = "0190f7aa-0000-7000-8000-000000000001";
const ACCOUNT_ID = "0190f7bb-0000-7000-8000-000000000001";

const state = { permissions: new Set<string>(), reuseAsked: false };

const summary: CustomerSummary = {
  customerId: CUSTOMER_ID,
  displayName: "K. Perera",
  displayNameSi: "කේ. පෙරේරා",
  language: "si",
  phone: "+94700000101",
  status: "ACTIVE",
  accountId: ACCOUNT_ID,
  accountNo: "A00001",
  creditLimit: 15000,
  balance: 14200
};

const account: Account = {
  accountId: ACCOUNT_ID,
  accountNo: "A00001",
  customerId: CUSTOMER_ID,
  creditLimit: 15000,
  balance: 14200,
  available: 800,
  offlineCap: 5000,
  termsDays: 30,
  hardBlock: false,
  status: "OPEN",
  openedAt: "2026-08-04T04:00:00Z",
  oldestUnpaid: "2026-08-05",
  unallocated: 0,
  ageing: { days0To30: 6400, days31To60: 7800, days61To90: 0, over90: 0 }
};

function card(): CustomerCard {
  return {
    customerId: CUSTOMER_ID,
    displayName: "K. Perera",
    displayNameSi: "කේ. පෙරේරා",
    language: "si",
    phone: "+94700000101",
    nicLast4: "0001",
    status: "ACTIVE",
    registeredAt: "2026-08-04T04:00:00Z",
    registeredHere: true,
    attributes: {},
    tags: ["regular"],
    consents: [{ purpose: "CREDIT_ACCOUNT", grantedVia: "PAPER", grantedAt: "2026-08-04T04:00:00Z" }],
    phones: [{ phone: "+94700000101", validFrom: "2026-08-04T04:00:00Z" }],
    account
  };
}

const api = {
  search: vi.fn(async () => [summary]),
  customer: vi.fn(async () => card()),
  register: vi.fn(async () => {
    if (!state.reuseAsked) {
      state.reuseAsked = true;
      throw new ApiProblem({ status: 422, code: "m7.customer.phone_reuse_confirm", title: "Someone else had this number recently" });
    }
    return card();
  }),
  openAccount: vi.fn(async () => account),
  account: vi.fn(async () => account),
  statement: vi.fn(async () => null),
  recordPayment: vi.fn(async () => ({
    documentId: "d1",
    docNumber: "M101-CPR-0000007",
    accountId: ACCOUNT_ID,
    amount: 2000,
    allocated: 2000,
    unallocated: 0,
    balance: 12200
  }))
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
            <Route path="/customers" element={<CustomersPage />} />
            <Route path="/customers/:customerId" element={<CustomerCardPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("the member register", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cus.customer.view", "cus.customer.register"]);
    state.reuseAsked = false;
  });
  afterEach(cleanup);

  it("lists the members with their account and balance", async () => {
    renderAt("/customers");
    expect(await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByText("A00001")).toBeTruthy();
  }, 15000);

  it("asks the officer to confirm a reused number, then registers with the confirmation", async () => {
    renderAt("/customers");
    await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 });
    fireEvent.change(screen.getByLabelText(text("customers.field.name")), { target: { value: "Anoma Herath" } });
    fireEvent.change(screen.getByLabelText(text("customers.field.phone")), { target: { value: "0700000102" } });
    fireEvent.click(screen.getByLabelText(text("customers.consent.credit")));
    fireEvent.click(screen.getByRole("button", { name: text("customers.register.submit") }));

    const confirm = await screen.findByLabelText(text("customers.register.confirm_identity"));
    expect(screen.getByRole("alert").textContent).toContain("Someone else had this number recently");
    expect((screen.getByRole("button", { name: text("customers.register.submit") }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(confirm);
    fireEvent.click(screen.getByRole("button", { name: text("customers.register.submit") }));

    await waitFor(() => expect(api.register).toHaveBeenCalledTimes(2));
    const [second] = api.register.mock.calls[1] as unknown as [Record<string, unknown>];
    expect(second).toMatchObject({
      displayName: "Anoma Herath",
      phone: "0700000102",
      consents: ["CREDIT_ACCOUNT"],
      via: "PAPER",
      confirmedIdentity: true
    });
  }, 15000);

  it("offers no registration to a role that may only read", async () => {
    state.permissions = new Set(["cus.customer.view"]);
    renderAt("/customers");
    await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 });
    expect(screen.queryByRole("button", { name: text("customers.register.submit") })).toBeNull();
    expect(screen.getByText(text("customers.read_only"))).toBeTruthy();
  }, 15000);
});

describe("the customer card", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cus.customer.view", "cus.payment.record"]);
  });
  afterEach(cleanup);

  it("shows the account near its limit and records a repayment at the office", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    expect(await screen.findByText("****0001", undefined, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByText(text("customers.account.near_limit").replace("{percent}", "94"))).toBeTruthy();

    fireEvent.change(screen.getByLabelText(text("customers.payment.amount")), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText(text("customers.payment.method")), { target: { value: "TRANSFER" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.payment.record") }));

    await waitFor(() => expect(api.recordPayment).toHaveBeenCalledOnce());
    const [accountId, body] = api.recordPayment.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(accountId).toBe(ACCOUNT_ID);
    expect(body).toMatchObject({ method: "TRANSFER", amount: 2000, allocationMode: "OLDEST_FIRST" });
    expect(await screen.findByRole("status")).toBeTruthy();
    expect(screen.getByRole("status").textContent).toContain("M101-CPR-0000007");
  }, 15000);
});
