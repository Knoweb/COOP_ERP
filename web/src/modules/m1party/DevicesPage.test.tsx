import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { Device, DeviceSyncStatus, EnrolmentCode, SequenceGap } from "./deviceApi";
import { DevicesPage } from "./DevicesPage";

const DEVICE_ID = "0190f0de-0000-7000-8000-00000000dd01";

const sampleDevice: Device = {
  deviceId: DEVICE_ID,
  ownerEntityId: "0190f0de-0000-7000-8000-000000000001",
  locationId: "0190f0de-0000-7000-8000-000000000101",
  hardwareSerial: "POS-SN-998811",
  deviceKind: "POS_TERMINAL",
  status: "ACTIVE",
  primaryTill: true,
  tillPositionId: "0190f0de-0000-7000-8000-000000000201",
  lastSeenAt: "2026-11-25T10:00:00Z"
};

const sampleCode: EnrolmentCode = {
  device_id: DEVICE_ID,
  code: "ABCD-EFGH-1234-5678",
  expires_at: "2026-11-26T10:00:00Z"
};

const sampleSyncStatus: DeviceSyncStatus = {
  device_id: DEVICE_ID,
  owner_entity_id: "0190f0de-0000-7000-8000-000000000001",
  last_applied_seq: 42,
  last_applied_at: "2026-11-25T10:00:00Z",
  last_batch_id: "0190f0de-0000-7000-8000-00000000bb01",
  in_flight_batch_id: null,
  in_flight_since: null,
  gap_rejections: 0,
  resend_from_seq: null,
  snapshot_version_reported: 12,
  app_version: "2.1.0",
  last_enrolled_at: "2026-11-20T08:00:00Z",
  last_seen_at: "2026-11-25T10:00:00Z",
  battery_pct: 95,
  storage_free_mb: 4096,
  pending_event_count: 0,
  last_acknowledged_seq: 42,
  clock_offset_ms: 12,
  open_session: true,
  recent_gaps: []
};

const sampleGap: SequenceGap = {
  gap_id: "0190f0de-0000-7000-8000-00000000gg01",
  device_id: DEVICE_ID,
  from_seq: 43,
  to_seq: 49,
  recorded_at: "2026-11-25T10:05:00Z"
};

const api = {
  listDevices: vi.fn(async () => [sampleDevice]),
  getDevice: vi.fn(async () => sampleDevice),
  issueEnrolmentCode: vi.fn(async () => sampleCode),
  getDeviceSyncStatus: vi.fn(async () => sampleSyncStatus),
  resetDeviceSequence: vi.fn(async () => sampleGap),
  listQuarantine: vi.fn(async () => []),
  getQuarantine: vi.fn(async () => ({} as unknown)),
  resolveQuarantine: vi.fn(async () => ({} as unknown))
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

function renderDevices() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <DevicesPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("DevicesPage: Device enrolment and sync-status with sequence reset", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hasPermission = true;
  });
  afterEach(cleanup);

  it("An administrator enrols a till by generating an enrolment code", async () => {
    renderDevices();

    expect(await screen.findByText("POS-SN-998811")).toBeTruthy();
    expect(screen.getAllByText("ACTIVE").length).toBeGreaterThanOrEqual(1);

    const enrolBtn = screen.getByRole("button", { name: text("party.device.action.enrol_code") });
    fireEvent.click(enrolBtn);

    await waitFor(() => {
      expect(api.issueEnrolmentCode).toHaveBeenCalledWith(DEVICE_ID, expect.any(String));
    });

    expect(await screen.findByText("ABCD-EFGH-1234-5678")).toBeTruthy();
    expect(screen.getByText(text("party.device.enrol.title"))).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: text("party.device.close") }));
    await waitFor(() => {
      expect(screen.queryByText("ABCD-EFGH-1234-5678")).toBeNull();
    });
  });

  it("An administrator inspects sync status and resets a sequence on screen", async () => {
    renderDevices();

    expect(await screen.findByText("POS-SN-998811")).toBeTruthy();

    // 1. Open sync status
    const statusBtn = screen.getByRole("button", { name: text("party.device.action.sync_status") });
    fireEvent.click(statusBtn);

    await waitFor(() => {
      expect(api.getDeviceSyncStatus).toHaveBeenCalledWith(DEVICE_ID);
    });

    expect(await screen.findByText("42")).toBeTruthy();
    expect(screen.getByText("95%")).toBeTruthy();
    expect(screen.getByText("4096 MB")).toBeTruthy();

    // 2. Open sequence reset from sync status
    const resetButtons = screen.getAllByRole("button", { name: text("party.device.action.reset_seq") });
    fireEvent.click(resetButtons[resetButtons.length - 1]);

    expect(await screen.findByText(`${text("party.device.reset.title")}: POS-SN-998811`)).toBeTruthy();

    // 3. Enter new sequence and reason
    const seqInput = screen.getByLabelText(text("party.device.reset.new_seq"));
    fireEvent.change(seqInput, { target: { value: "50" } });

    const reasonSelect = screen.getByLabelText(text("party.device.reset.reason_code"));
    fireEvent.change(reasonSelect, { target: { value: "OUTBOX_LOST" } });

    const reasonText = screen.getByLabelText(text("party.device.reset.reason_text"));
    fireEvent.change(reasonText, { target: { value: "Till storage cleared" } });

    // 4. Submit sequence reset
    const submitButtons = screen.getAllByRole("button", { name: text("party.device.reset.submit") });
    fireEvent.click(submitButtons[submitButtons.length - 1]);

    await waitFor(() => {
      expect(api.resetDeviceSequence).toHaveBeenCalledWith(
        DEVICE_ID,
        {
          new_start_seq: 50,
          reason_code: "OUTBOX_LOST",
          reason_text: "Till storage cleared"
        },
        expect.any(String)
      );
    });

    // 5. Verify success feedback
    expect(
      await screen.findByText(text("party.device.reset.success", { nextSeq: 50 }))
    ).toBeTruthy();
  });

  it("Hides enrolment and sequence reset actions for a user without enrol permissions", async () => {
    hasPermission = false;
    renderDevices();

    expect(await screen.findByText("POS-SN-998811")).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("party.device.action.enrol_code") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("party.device.action.reset_seq") })).toBeNull();
    expect(screen.getByRole("button", { name: text("party.device.action.sync_status") })).toBeTruthy();
  });
});
