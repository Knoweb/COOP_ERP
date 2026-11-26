import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { QuarantinedEvent, ResolvedQuarantine } from "./deviceApi";
import { QuarantinePage } from "./QuarantinePage";

const QUARANTINE_ID = "0190f0de-0000-7000-8000-00000000qq01";
const DEVICE_ID = "0190f0de-0000-7000-8000-00000000dd01";

const pendingEvent: QuarantinedEvent = {
  quarantine_id: QUARANTINE_ID,
  device_id: DEVICE_ID,
  device_seq: 15,
  owner_entity_id: "0190f0de-0000-7000-8000-000000000001",
  location_id: "0190f0de-0000-7000-8000-000000000101",
  batch_id: "0190f0de-0000-7000-8000-00000000bb01",
  event_id: "0190f0de-0000-7000-8000-00000000ee01",
  event_type: "pos.receipt.issued.v1",
  reason: "SCHEMA",
  detail: "receipt total mismatch with lines",
  raw_event: '{"event_type":"pos.receipt.issued.v1","total":"1500.00"}',
  received_at: "2026-11-25T11:00:00Z",
  resolved_at: null,
  resolved_by_user_id: null,
  resolution: null,
  resolution_reason: null,
  series_id: null,
  doc_number: null
};

const resolvedEvent: QuarantinedEvent = {
  ...pendingEvent,
  quarantine_id: "0190f0de-0000-7000-8000-00000000qq02",
  device_seq: 14,
  resolved_at: "2026-11-25T11:30:00Z",
  resolved_by_user_id: "0190f0de-0000-7000-8000-00000000uu01",
  resolution: "REPAIRED",
  resolution_reason: "RESENT_CORRECTED: Resent as batch 10"
};

const sampleResolved: ResolvedQuarantine = {
  quarantine_id: QUARANTINE_ID,
  device_id: DEVICE_ID,
  device_seq: 15,
  reason: "SCHEMA",
  resolution: "REPAIRED",
  resolved_at: "2026-11-25T12:00:00Z"
};

const api = {
  listDevices: vi.fn(async () => []),
  getDevice: vi.fn(async () => ({} as unknown)),
  issueEnrolmentCode: vi.fn(async () => ({} as unknown)),
  getDeviceSyncStatus: vi.fn(async () => ({} as unknown)),
  resetDeviceSequence: vi.fn(async () => ({} as unknown)),
  listQuarantine: vi.fn(async () => [pendingEvent, resolvedEvent]),
  getQuarantine: vi.fn(async () => pendingEvent),
  resolveQuarantine: vi.fn(async () => sampleResolved)
};

let hasPermission = true;

vi.mock("./deviceApi", () => ({ useDeviceApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: () => hasPermission }));

const text = (id: string, values?: Record<string, string | number>) => {
  let str = (messages.en as Record<string, string>)[id] ?? id;
  if (values) {
    for (const [k, v] of Object.entries(values)) {
      str = str.replace(`{${k}}`, String(v));
    }
  }
  return str;
};

function renderQuarantine() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <QuarantinePage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("QuarantinePage: Find and resolve quarantined events", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hasPermission = true;
  });
  afterEach(cleanup);

  it("An administrator finds and resolves a quarantined event on screen", async () => {
    renderQuarantine();

    expect((await screen.findAllByText("SCHEMA")).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("pos.receipt.issued.v1").length).toBeGreaterThanOrEqual(1);

    // 1. Inspect the pending event
    const inspectBtns = screen.getAllByRole("button", { name: text("party.quarantine.action.inspect") });
    fireEvent.click(inspectBtns[0]);

    expect(await screen.findByText(QUARANTINE_ID)).toBeTruthy();
    expect(screen.getByText("receipt total mismatch with lines")).toBeTruthy();
    expect(screen.getByText(/"total": "1500.00"/)).toBeTruthy();

    // 2. Select resolution and fill reason
    const repairedRadio = screen.getByLabelText(text("party.quarantine.resolve.repaired"));
    expect(repairedRadio).toBeTruthy();

    const reasonSelect = screen.getByLabelText(text("party.quarantine.resolve.reason_code"));
    fireEvent.change(reasonSelect, { target: { value: "RESENT_CORRECTED" } });

    const reasonText = screen.getByLabelText(text("party.quarantine.resolve.reason_text"));
    fireEvent.change(reasonText, { target: { value: "Corrected receipt uploaded" } });

    // 3. Submit resolution
    const submitBtn = screen.getByRole("button", { name: text("party.quarantine.resolve.submit") });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(api.resolveQuarantine).toHaveBeenCalledWith(
        QUARANTINE_ID,
        {
          resolution: "REPAIRED",
          reason_code: "RESENT_CORRECTED",
          reason_text: "Corrected receipt uploaded"
        },
        expect.any(String)
      );
    });

    // 4. Verify resolution success
    expect(
      await screen.findByText(text("party.quarantine.resolve.success", { resolution: "REPAIRED" }))
    ).toBeTruthy();
  });

  it("Displays resolved quarantine details without resolution form", async () => {
    renderQuarantine();

    expect((await screen.findAllByText("SCHEMA")).length).toBeGreaterThanOrEqual(1);

    // Inspect the resolved event (index 1)
    const inspectBtns = screen.getAllByRole("button", { name: text("party.quarantine.action.inspect") });
    fireEvent.click(inspectBtns[1]);

    expect(await screen.findByText(text("party.quarantine.modal.resolution_info"))).toBeTruthy();
    expect(screen.getByText("RESENT_CORRECTED: Resent as batch 10")).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("party.quarantine.resolve.submit") })).toBeNull();
  });
});
