import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { messages } from "../../shell/i18n/messages";
import type {
  Account,
  CustomerCard,
  CustomerSummary,
  Location,
  LoyaltyCustomerResponse,
  LoyaltyEligibilityResponse
} from "./customersApi";
import { CustomerCardPage } from "./CustomerCardPage";
import { CustomersPage } from "./CustomersPage";

const CUSTOMER_ID = "0190f7aa-0000-7000-8000-000000000001";
const ACCOUNT_ID = "0190f7bb-0000-7000-8000-000000000001";
const LOCATION_ID = "0190f7dd-0000-7000-8000-000000000101";

const state = {
  permissions: new Set<string>(),
  reuseAsked: false,
  loyaltyProfile: null as LoyaltyCustomerResponse | null
};

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

const location: Location = {
  locationId: LOCATION_ID,
  ownerEntityId: "0190f700-0000-7000-8000-000000000001",
  locationCode: "SHOP-01",
  nameEn: "Main Town Shop",
  nameSi: "ප්‍රධාන නගර වෙළඳසැල",
  nameTa: "பிரதான நகர கடை",
  locationType: "SHOP",
  connectivitySpecMet: true,
  status: "ACTIVE"
};

const defaultLoyalty: LoyaltyCustomerResponse = {
  loyaltyId: "0190f7cc-0000-7000-8000-000000000001",
  customerId: CUSTOMER_ID,
  ownerEntityId: "0190f700-0000-7000-8000-000000000001",
  displayName: "K. Perera",
  phone: "+94700000101",
  registeredLocationId: LOCATION_ID,
  regionCode: "REG-WEST",
  regionalMemberNo: "MEM-777",
  loyaltyTier: "MEMBER",
  status: "ACTIVE",
  isMember: true,
  allLocationsPermitted: true,
  permittedLocationIds: []
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
  history: vi.fn(async () => []),
  adjustments: vi.fn(async () => []),
  statement: vi.fn(async () => null),
  recordPayment: vi.fn(async () => ({
    documentId: "d1",
    docNumber: "M101-CPR-0000007",
    accountId: ACCOUNT_ID,
    amount: 2000,
    allocated: 2000,
    unallocated: 0,
    balance: 12200
  })),
  locations: vi.fn(async () => [location]),
  lookupLoyalty: vi.fn(async ({ memberNo, customerId }: { phone?: string; nic?: string; customerId?: string; memberNo?: string }) => {
    if (memberNo === "MEM-777" || (customerId === CUSTOMER_ID && state.loyaltyProfile !== null)) {
      return state.loyaltyProfile;
    }
    return null;
  }),
  checkLoyaltyEligibility: vi.fn(async (): Promise<LoyaltyEligibilityResponse> => ({
    eligible: true,
    status: "ACTIVE",
    reason: "Member in good standing",
    isMember: true,
    memberNo: "MEM-777",
    loyaltyTier: "MEMBER"
  })),
  registerLoyalty: vi.fn(async (body: Record<string, unknown>) => {
    if (body.regionalMemberNo === "DUPLICATE-NO") {
      throw new ApiProblem({ status: 422, code: "m7.loyalty.already_enrolled", title: "Member number already registered" });
    }
    state.loyaltyProfile = defaultLoyalty;
    return defaultLoyalty;
  }),
  updateRegionalRegistration: vi.fn(async (_custId: string, body: Record<string, unknown>) => {
    if (body.regionalMemberNo === "DUPLICATE-NO") {
      throw new ApiProblem({ status: 422, code: "m7.loyalty.already_enrolled", title: "Member number already registered" });
    }
    state.loyaltyProfile = { ...defaultLoyalty, ...body } as LoyaltyCustomerResponse;
    return state.loyaltyProfile;
  })
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
    state.loyaltyProfile = defaultLoyalty;
  });
  afterEach(cleanup);

  it("lists the members with their account and balance", async () => {
    renderAt("/customers");
    expect(await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 })).toBeTruthy();
    expect(screen.getByText("A00001")).toBeTruthy();
  }, 15000);

  it("searches by regional member number using the loyalty lookup", async () => {
    renderAt("/customers");
    await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 });

    const memberInput = screen.getByLabelText(text("customers.search.member_no"));
    expect(memberInput).toBeTruthy();

    fireEvent.change(memberInput, { target: { value: "MEM-777" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.search.find") }));

    await waitFor(() => {
      expect(api.lookupLoyalty).toHaveBeenCalledWith({ memberNo: "MEM-777" });
    });
    expect(await screen.findByRole("link", { name: "K. Perera" })).toBeTruthy();
  }, 15000);

  it("shows friendly not found message when searched member number does not exist", async () => {
    renderAt("/customers");
    await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 });

    const memberInput = screen.getByLabelText(text("customers.search.member_no"));
    fireEvent.change(memberInput, { target: { value: "UNKNOWN-999" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.search.find") }));

    await waitFor(() => {
      expect(api.lookupLoyalty).toHaveBeenCalledWith({ memberNo: "UNKNOWN-999" });
    });
    expect(await screen.findByText(text("customers.loyalty.not_found"))).toBeTruthy();
  }, 15000);

  it("allows registering an ordinary member with both consent checkboxes unticked (zero consents)", async () => {
    renderAt("/customers");
    await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 });
    state.reuseAsked = true;

    fireEvent.change(screen.getByLabelText(text("customers.field.name")), { target: { value: "Kamal Perera" } });
    fireEvent.change(screen.getByLabelText(text("customers.field.phone")), { target: { value: "0722222222" } });

    // The submit button must be enabled with both checkboxes unticked
    const submitBtn = screen.getByRole("button", { name: text("customers.register.submit") }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(false);

    fireEvent.click(submitBtn);

    await waitFor(() => expect(api.register).toHaveBeenCalledOnce());
    const [body] = api.register.mock.calls[0] as unknown as [Record<string, unknown>];
    expect(body).toMatchObject({
      displayName: "Kamal Perera",
      phone: "0722222222",
      consents: [],
      via: "PAPER"
    });
  }, 15000);

  it("allows registering an ordinary member without credit-account consent but with SMS consent", async () => {
    renderAt("/customers");
    await screen.findByRole("link", { name: "K. Perera" }, { timeout: 10000 });
    state.reuseAsked = true;

    fireEvent.change(screen.getByLabelText(text("customers.field.name")), { target: { value: "Sunil Silva" } });
    fireEvent.change(screen.getByLabelText(text("customers.field.phone")), { target: { value: "0711111111" } });

    // The submit button must NOT be disabled when credit consent is not checked
    const submitBtn = screen.getByRole("button", { name: text("customers.register.submit") }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(false);

    // Ticking statements consent
    fireEvent.click(screen.getByLabelText(text("customers.consent.statements")));
    fireEvent.click(submitBtn);

    await waitFor(() => expect(api.register).toHaveBeenCalledOnce());
    const [body] = api.register.mock.calls[0] as unknown as [Record<string, unknown>];
    expect(body).toMatchObject({
      displayName: "Sunil Silva",
      phone: "0711111111",
      consents: ["STATEMENTS_NOTIFICATIONS"],
      via: "PAPER"
    });
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
    state.permissions = new Set(["cus.customer.view", "cus.payment.record", "cus.customer.manage"]);
    state.loyaltyProfile = defaultLoyalty;
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

  it("displays regional membership & loyalty details and allows updating registration", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    expect(await screen.findByRole("heading", { name: text("customers.loyalty.title") })).toBeTruthy();

    // Fact list assertions
    expect(screen.getByText("MEM-777")).toBeTruthy();
    expect(screen.getByText(text("customers.loyalty.is_member_yes"))).toBeTruthy();
    expect(screen.getAllByText(text("customers.loyalty.tier.MEMBER")).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(text("customers.loyalty.status.ACTIVE")).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("REG-WEST")).toBeTruthy();
    expect(screen.getAllByText("Main Town Shop").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(text("customers.loyalty.scope.all_locations"))).toBeTruthy();

    // Update form
    expect(screen.getByRole("heading", { name: text("customers.loyalty.update.title") })).toBeTruthy();
    fireEvent.change(screen.getByLabelText(text("customers.loyalty.region_code")), { target: { value: "REG-NORTH" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.loyalty.update.submit") }));

    await waitFor(() => expect(api.updateRegionalRegistration).toHaveBeenCalledOnce());
    const [custId, updateBody] = api.updateRegionalRegistration.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(custId).toBe(CUSTOMER_ID);
    expect(updateBody).toMatchObject({
      regionCode: "REG-NORTH",
      loyaltyTier: "MEMBER",
      status: "ACTIVE"
    });
  }, 15000);

  it("surfaces duplicate member number error from ApiProblem on update", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    await screen.findByRole("heading", { name: text("customers.loyalty.title") });

    fireEvent.change(screen.getByLabelText(text("customers.loyalty.regional_member_no")), { target: { value: "DUPLICATE-NO" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.loyalty.update.submit") }));

    await waitFor(() => expect(api.updateRegionalRegistration).toHaveBeenCalledOnce());
    expect(await screen.findByText("Member number already registered")).toBeTruthy();
  }, 15000);

  it("performs read-only loyalty eligibility check and displays result", async () => {
    renderAt(`/customers/${CUSTOMER_ID}`);
    await screen.findByRole("heading", { name: text("customers.loyalty.title") });

    fireEvent.click(screen.getByRole("button", { name: text("customers.loyalty.eligibility.check") }));

    await waitFor(() => expect(api.checkLoyaltyEligibility).toHaveBeenCalledOnce());
    expect(await screen.findByText(text("customers.loyalty.eligibility.eligible"))).toBeTruthy();
    expect(screen.getByText("Member in good standing")).toBeTruthy();
  }, 15000);

  it("shows enrollment form when customer is not yet enrolled in loyalty", async () => {
    state.loyaltyProfile = null;
    state.permissions = new Set(["cus.customer.view", "cus.customer.register"]);

    renderAt(`/customers/${CUSTOMER_ID}`);
    expect(await screen.findByText(text("customers.loyalty.not_enrolled"))).toBeTruthy();
    expect(screen.getByRole("heading", { name: text("customers.loyalty.enroll.title") })).toBeTruthy();

    fireEvent.change(screen.getByLabelText(text("customers.loyalty.region_code")), { target: { value: "REG-SOUTH" } });
    fireEvent.change(screen.getByLabelText(text("customers.loyalty.regional_member_no")), { target: { value: "MEM-888" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.loyalty.enroll.submit") }));

    await waitFor(() => expect(api.registerLoyalty).toHaveBeenCalledOnce());
    const [enrollBody] = api.registerLoyalty.mock.calls[0] as unknown as [Record<string, unknown>];
    expect(enrollBody).toMatchObject({
      customerId: CUSTOMER_ID,
      regionCode: "REG-SOUTH",
      regionalMemberNo: "MEM-888"
    });
  }, 15000);

  it("refuses opening a credit account when customer has no credit account consent", async () => {
    state.permissions = new Set(["cus.customer.view", "cus.account.manage"]);
    api.customer.mockResolvedValueOnce({
      ...card(),
      account: undefined,
      consents: []
    });
    api.openAccount.mockRejectedValueOnce(
      new ApiProblem({
        status: 422,
        code: "m7.customer.consent_required",
        title: "The customer must agree to a credit account"
      })
    );

    renderAt(`/customers/${CUSTOMER_ID}`);
    expect(await screen.findByRole("heading", { name: text("customers.account.open.title") })).toBeTruthy();

    fireEvent.change(screen.getByLabelText(text("customers.account.limit")), { target: { value: "5000" } });
    fireEvent.click(screen.getByRole("button", { name: text("customers.account.open.submit") }));

    await waitFor(() => expect(api.openAccount).toHaveBeenCalledOnce());
    expect(await screen.findByText("The customer must agree to a credit account")).toBeTruthy();
  }, 15000);
});
