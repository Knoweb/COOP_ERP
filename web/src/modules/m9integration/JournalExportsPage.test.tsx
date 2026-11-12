import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { businessToday } from "../../shell/i18n/formats";
import { messages } from "../../shell/i18n/messages";
import { defaultExportPeriod } from "./integrationView";
import { JournalExportsPage } from "./JournalExportsPage";
import type { JournalExport } from "./integrationApi";

const EXPORT_ID = "0190fa00-0000-7000-8000-000000000301";
const state = {
  permissions: new Set<string>(),
  status: "GENERATED" as JournalExport["status"]
};

function exportRow(): JournalExport {
  return {
    exportId: EXPORT_ID,
    periodFrom: "2026-08-01",
    periodTo: "2026-08-31",
    format: "CSV",
    status: state.status,
    provisional: false,
    lineCount: 2,
    totalDebit: 100,
    totalCredit: 100,
    contentHash: "a".repeat(64),
    generatedAt: "2026-09-01T10:00:00Z"
  };
}

const mockApi = {
  exports: vi.fn(async () => [exportRow()]),
  pending: vi.fn(async () => ({ postings: 0, amount: 0 })),
  supplementDue: vi.fn(async () => ({ postings: 0, amount: 0 })),
  acknowledgeExport: vi.fn(async (...args: [string, string]) => {
    void args;
    state.status = "ACKNOWLEDGED";
    return exportRow();
  })
};

vi.mock("./integrationApi", () => ({
  useIntegrationApi: () => mockApi
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });

  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <JournalExportsPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("JournalExportsPage acknowledgement (ACC-03)", () => {
  beforeEach(() => {
    state.permissions.clear();
    state.status = "GENERATED";
    vi.clearAllMocks();
  });

  afterEach(cleanup);

  it("hides acknowledgement without permission", async () => {
    renderPage();

    await screen.findByRole("button", { name: "Reconciliation" });

    expect(
      screen.queryByRole("button", { name: "Acknowledge receipt" })
    ).toBeNull();
  });

  it("acknowledges a generated export and refreshes its status", async () => {
    state.permissions.add("int.journal.ack");
    renderPage();

    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge receipt" })
    );

    await waitFor(() => {
      expect(mockApi.acknowledgeExport).toHaveBeenCalledWith(
        EXPORT_ID,
        expect.any(String)
      );
      expect(screen.getByText("Acknowledged")).toBeTruthy();
    });

    expect(
      screen.queryByRole("button", { name: "Acknowledge receipt" })
    ).toBeNull();
  });

  it("does not offer acknowledgement for an acknowledged export", async () => {
    state.permissions.add("int.journal.ack");
    state.status = "ACKNOWLEDGED";
    renderPage();

    await screen.findByText("Acknowledged");

    expect(
      screen.queryByRole("button", { name: "Acknowledge receipt" })
    ).toBeNull();
  });

  it("reuses the idempotency key after a failed request", async () => {
    state.permissions.add("int.journal.ack");
    mockApi.acknowledgeExport.mockRejectedValueOnce(
      new Error("Network failed")
    );

    renderPage();

    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge receipt" })
    );

    await screen.findByRole("alert");

    fireEvent.click(
      screen.getByRole("button", { name: "Acknowledge receipt" })
    );

    await waitFor(() => {
      expect(mockApi.acknowledgeExport).toHaveBeenCalledTimes(2);
    });

    const first = mockApi.acknowledgeExport.mock.calls[0];
    const second = mockApi.acknowledgeExport.mock.calls[1];

    expect(first[0]).toBe(EXPORT_ID);
    expect(second[0]).toBe(EXPORT_ID);
    expect(second[1]).toBe(first[1]);
  });
});

function formatDmy(iso: string): string {
  const [y, m, d] = iso.split("-");
  return `${d}/${m}/${y}`;
}

describe("JournalExportsPage date pickers (.cdp)", () => {
  beforeEach(() => {
    state.permissions.clear();
    state.status = "GENERATED";
    vi.clearAllMocks();
  });

  afterEach(cleanup);

  it("picks From and To dates using custom .cdp calendar dialogs and closes on Escape", async () => {
    const initial = defaultExportPeriod(businessToday());
    const fromPrefix = initial.from.slice(0, 7);
    const toPrefix = initial.to.slice(0, 7);
    const expectedFromDisplay = formatDmy(initial.from);
    const expectedToDisplay = formatDmy(initial.to);
    const expectedPickedFromIso = `${fromPrefix}-15`;
    const expectedPickedToIso = `${toPrefix}-10`;

    renderPage();

    const fromBtn = await screen.findByRole("button", { name: `From: ${expectedFromDisplay}` });
    const toBtn = screen.getByRole("button", { name: `To: ${expectedToDisplay}` });

    fireEvent.click(fromBtn);
    const fromDialog = screen.getByRole("dialog", { name: "From" });
    expect(fromDialog.className).toContain("cdp__popup");
    fireEvent.click(within(fromDialog).getByRole("button", { name: "15" }));

    expect(screen.queryByRole("dialog", { name: "From" })).toBeNull();
    expect(screen.getByRole("button", { name: `From: ${formatDmy(expectedPickedFromIso)}` })).toBeDefined();
    await waitFor(() => {
      expect(mockApi.pending).toHaveBeenCalledWith(expectedPickedFromIso, initial.to);
    });

    fireEvent.click(toBtn);
    const toDialog = screen.getByRole("dialog", { name: "To" });
    expect(toDialog.className).toContain("cdp__popup");
    fireEvent.click(within(toDialog).getByRole("button", { name: "10" }));

    expect(screen.queryByRole("dialog", { name: "To" })).toBeNull();
    expect(screen.getByRole("button", { name: `To: ${formatDmy(expectedPickedToIso)}` })).toBeDefined();
    await waitFor(() => {
      expect(mockApi.pending).toHaveBeenCalledWith(expectedPickedFromIso, expectedPickedToIso);
    });

    fireEvent.click(screen.getByRole("button", { name: `From: ${formatDmy(expectedPickedFromIso)}` }));
    expect(screen.getByRole("dialog", { name: "From" })).toBeDefined();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "From" })).toBeNull();
  });

  it("toggles the Provisional pill checked state class when clicked", async () => {
    renderPage();

    await screen.findByRole("button", { name: "Reconciliation" });
    const checkbox = screen.getByRole("checkbox") as HTMLInputElement;
    const pill = checkbox.closest("label");

    expect(checkbox.checked).toBe(false);
    expect(pill?.className).toContain("integration-check");
    expect(pill?.className).not.toContain("integration-check--checked");

    fireEvent.click(checkbox);

    expect(checkbox.checked).toBe(true);
    expect(pill?.className).toContain("integration-check--checked");

    fireEvent.click(checkbox);

    expect(checkbox.checked).toBe(false);
    expect(pill?.className).not.toContain("integration-check--checked");
  });
});
