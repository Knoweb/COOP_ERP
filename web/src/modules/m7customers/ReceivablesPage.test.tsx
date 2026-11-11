import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Receivable } from "./customersApi";
import { ReceivablesPage } from "./ReceivablesPage";

const state = { canView: true };

const readReceivables = vi.fn(
  async (...args: [number, number]): Promise<Receivable[]> => {
    void args;
    return [];
  }
);

vi.mock("./customersApi", () => ({
  useCustomersApi: () => ({ receivables: readReceivables })
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (permission: string) =>
    permission === "cus.customer.view" && state.canView
}));

function receivable(index: number): Receivable {
  const id = String(index + 1).padStart(12, "0");

  return {
    displayName: `Member ${index + 1}`,
    account: {
      accountId: `0190f7bb-0000-7000-8000-${id}`,
      customerId: `0190f7aa-0000-7000-8000-${id}`,
      accountNo: `A${String(index + 1).padStart(5, "0")}`,
      creditLimit: 10000,
      balance: 2500,
      available: 7500,
      termsDays: 30,
      hardBlock: false,
      status: "OPEN",
      openedAt: "2026-10-01T00:00:00Z",
      unallocated: 0,
      ageing: {
        days0To30: 1000,
        days31To60: 750,
        days61To90: 500,
        over90: 250
      }
    }
  };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });

  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/customers/receivables"]}>
          <ReceivablesPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("CUS-02 society receivables and ageing", () => {
  beforeEach(() => {
    readReceivables.mockReset();
    readReceivables.mockResolvedValue([]);
    state.canView = true;
  });

  afterEach(cleanup);

  it("shows outstanding accounts and ageing columns", async () => {
    readReceivables.mockResolvedValue([receivable(0)]);
    renderPage();

    expect(await screen.findByText("Member 1")).toBeTruthy();
    expect(screen.getByText("A00001")).toBeTruthy();

    for (const label of [
      "0–30 days",
      "31–60 days",
      "61–90 days",
      "Over 90 days"
    ]) {
      expect(screen.getByRole("columnheader", { name: label })).toBeTruthy();
    }

    expect(readReceivables).toHaveBeenCalledWith(0, 51);
  });

  it("shows an empty state", async () => {
    renderPage();

    expect(
      await screen.findByText("No outstanding credit accounts found.")
    ).toBeTruthy();
  });

  it("moves between pages without repeating accounts", async () => {
    const entries = Array.from({ length: 51 }, (_, index) =>
      receivable(index)
    );

    readReceivables.mockImplementation(async (offset, limit) =>
      entries.slice(offset, offset + limit)
    );

    renderPage();

    expect(await screen.findByText("Member 1")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByText("Member 51")).toBeTruthy();

    await waitFor(() => {
      expect(screen.queryByText("Member 1")).toBeNull();
      expect(readReceivables).toHaveBeenCalledWith(50, 51);
    });

    fireEvent.click(screen.getByRole("button", { name: "Previous" }));

    expect(await screen.findByText("Member 1")).toBeTruthy();
  });

  it("does not fetch receivables without permission", () => {
    state.canView = false;
    renderPage();

    expect(
      screen.getByText(
        "You do not have permission to view customer receivables."
      )
    ).toBeTruthy();

    expect(readReceivables).not.toHaveBeenCalled();
  });

  it("displays API errors", async () => {
    readReceivables.mockRejectedValue(new Error("Service unavailable"));
    renderPage();

    expect(
      await screen.findByText("Something went wrong. Try again.")
    ).toBeTruthy();
  });
});
