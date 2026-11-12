import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { ConsentManagement } from "./ConsentManagement";
import type { CustomerCard } from "./customersApi";

const CUSTOMER_ID = "0190f7aa-0000-7000-8000-000000000099";

const api = {
  recordConsent: vi.fn(),
  withdrawConsent: vi.fn()
};

vi.mock("./customersApi", () => ({
  useCustomersApi: () => api
}));

function customer(consents: CustomerCard["consents"] = []): CustomerCard {
  return {
    customerId: CUSTOMER_ID,
    displayName: "Test Member",
    language: "en",
    phone: "+94700000999",
    status: "ACTIVE",
    registeredAt: "2026-08-04T04:00:00Z",
    registeredHere: true,
    attributes: {},
    tags: [],
    consents,
    phones: []
  };
}

const creditConsent: CustomerCard["consents"][number] = {
  purpose: "CREDIT_ACCOUNT",
  grantedVia: "PAPER",
  grantedAt: "2026-08-04T04:00:00Z"
};

function renderConsent(card: CustomerCard, canManage = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });

  render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <ConsentManagement customer={card} canManage={canManage} />
      </IntlProvider>
    </QueryClientProvider>
  );

  return client;
}

describe("CUS-04 customer consent management", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(cleanup);

  it("records a new statement consent", async () => {
    const initial = customer([creditConsent]);
    const updated = customer([
      creditConsent,
      {
        purpose: "STATEMENTS_NOTIFICATIONS",
        grantedVia: "WEB",
        grantedAt: "2026-11-12T04:00:00Z"
      }
    ]);

    api.recordConsent.mockResolvedValue(updated);

    const client = renderConsent(initial);

    fireEvent.change(screen.getByLabelText("Consent purpose"), {
      target: { value: "STATEMENTS_NOTIFICATIONS" }
    });

    fireEvent.change(screen.getByLabelText("Consent recorded via"), {
      target: { value: "WEB" }
    });

    fireEvent.click(screen.getByRole("button", { name: "Record consent" }));

    await waitFor(() => {
      expect(api.recordConsent).toHaveBeenCalledOnce();
    });

    expect(api.recordConsent).toHaveBeenCalledWith(
      CUSTOMER_ID,
      { purpose: "STATEMENTS_NOTIFICATIONS", via: "WEB" },
      expect.any(String)
    );

    await waitFor(() => {
      expect(client.getQueryData(["customers", "card", CUSTOMER_ID]))
        .toEqual(updated);
    });
  });

  it("withdraws active consent without removing history", async () => {
    const original = customer([
      creditConsent,
      {
        purpose: "STATEMENTS_NOTIFICATIONS",
        grantedVia: "PAPER",
        grantedAt: "2026-09-01T04:00:00Z",
        withdrawnAt: "2026-09-10T04:00:00Z"
      }
    ]);

    const updated = customer([
      {
        ...creditConsent,
        withdrawnAt: "2026-11-12T04:00:00Z"
      },
      original.consents[1]
    ]);

    api.withdrawConsent.mockResolvedValue(updated);

    const client = renderConsent(original);

    expect(screen.getByText(/Withdrawn on/)).toBeTruthy();

    fireEvent.click(
      screen.getByRole("button", {
        name: "Withdraw consent: Credit account"
      })
    );

    await waitFor(() => {
      expect(api.withdrawConsent).toHaveBeenCalledWith(
        CUSTOMER_ID,
        "CREDIT_ACCOUNT",
        expect.any(String)
      );
    });

    await waitFor(() => {
      expect(client.getQueryData(["customers", "card", CUSTOMER_ID]))
        .toEqual(updated);
    });

    expect(updated.consents).toHaveLength(2);
  });

  it("hides editing controls without management permission", () => {
    renderConsent(customer([creditConsent]), false);

    expect(screen.getByText("Credit account")).toBeTruthy();
    expect(screen.queryByRole("button", {
      name: "Withdraw consent: Credit account"
    })).toBeNull();
    expect(screen.queryByRole("button", {
      name: "Record consent"
    })).toBeNull();
  });

  it("does not permit editing a customer registered elsewhere", () => {
    renderConsent({
      ...customer([creditConsent]),
      registeredHere: false
    });

    expect(screen.queryByRole("button", {
      name: "Withdraw consent: Credit account"
    })).toBeNull();
    expect(screen.queryByRole("button", {
      name: "Record consent"
    })).toBeNull();
  });
});
