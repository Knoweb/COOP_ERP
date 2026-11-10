import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { NotificationLogEntry, NotificationRule, NotificationTemplate } from "./integrationApi";
import { NotificationsPage } from "./NotificationsPage";

const ENTITY_ID = "0190e9c0-0000-7000-8000-000000000001";
const FAILED_ID = "0190fa00-0000-7000-8000-000000000001";
const SENT_ID = "0190fa00-0000-7000-8000-000000000002";

const state = {
  permissions: new Set<string>(),
  retriedIds: [] as string[]
};

const sampleRules: NotificationRule[] = [
  {
    ruleId: "0190f9a0-0000-7000-8000-000000000007",
    name: "Low stock: store keeper alert by SMS",
    eventType: "inventory.low_stock_detected.v1",
    templateId: "low-stock-alert-sms",
    audienceKind: "ROLE_AT_OWNER",
    audienceRole: "STORE_KEEPER",
    channels: ["SMS"],
    status: "ACTIVE",
    federationWide: true
  },
  {
    ruleId: "0190f9a0-0000-7000-8000-000000000008",
    name: "Price update: customer notice by SMS",
    eventType: "price_list.published.v1",
    templateId: "price-update-sms",
    audienceKind: "ROLE_AT_OWNER",
    audienceRole: "CUSTOMER",
    channels: ["SMS"],
    status: "ACTIVE",
    federationWide: true
  },
  {
    ruleId: "0190f9a0-0000-7000-8000-000000000009",
    name: "Birthday greeting: member SMS alert",
    eventType: "customer.birthday_alert.v1",
    templateId: "birthday-greeting-sms",
    audienceKind: "CUSTOMER",
    audienceRole: "CUSTOMER",
    channels: ["SMS"],
    status: "ACTIVE",
    federationWide: true
  }
];

const sampleTemplates: NotificationTemplate[] = [
  {
    templateId: "low-stock-alert-sms",
    channel: "SMS",
    bodyEn: "Low stock alert: SKU {skuId} at location {locationId} is low.",
    placeholders: ["skuId", "locationId", "onHandQty", "reorderLevel"],
    status: "ACTIVE"
  },
  {
    templateId: "price-update-sms",
    channel: "SMS",
    bodyEn: "Price Update Notice: {kind} price list is now active.",
    placeholders: ["kind", "version", "applyFrom"],
    status: "ACTIVE"
  },
  {
    templateId: "birthday-greeting-sms",
    channel: "SMS",
    bodyEn: "Happy Birthday from your Cooperative Society! {customerRef}.",
    placeholders: ["customerRef", "greetingYear"],
    status: "ACTIVE"
  }
];

const sampleLogs: NotificationLogEntry[] = [
  {
    notificationId: FAILED_ID,
    createdAt: "2026-11-09T08:00:00Z",
    channel: "SMS",
    status: "FAILED",
    attempts: 3,
    templateId: "low-stock-alert-sms",
    audienceRole: "STORE_KEEPER",
    recipientTag: "077***01",
    lastError: "SendFailed: NETWORK_TIMEOUT",
    recipientEntityId: ENTITY_ID
  },
  {
    notificationId: SENT_ID,
    createdAt: "2026-11-09T08:30:00Z",
    channel: "SMS",
    status: "SENT",
    attempts: 1,
    templateId: "price-update-sms",
    audienceRole: "CUSTOMER",
    recipientTag: "077***02",
    recipientEntityId: ENTITY_ID
  }
];

const mockApi = {
  rules: vi.fn(async () => sampleRules),
  templates: vi.fn(async () => sampleTemplates),
  log: vi.fn(async () => sampleLogs),
  setRuleStatus: vi.fn(async () => sampleRules[0]),
  retryNotification: vi.fn(async (notificationId: string) => {
    state.retriedIds.push(notificationId);
    return { requestId: notificationId, status: "SENT" as const };
  })
};

vi.mock("./integrationApi", () => ({
  useIntegrationApi: () => mockApi
}));

vi.mock("../../shell/scope/useScope", () => ({
  useScope: () => ({ entityId: ENTITY_ID })
}));

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

describe("NotificationsPage (PGL-M3-02)", () => {
  beforeEach(() => {
    state.permissions.clear();
    state.retriedIds = [];
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  function renderPage() {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } }
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <IntlProvider locale="en" messages={messages.en}>
          <MemoryRouter>
            <NotificationsPage />
          </MemoryRouter>
        </IntlProvider>
      </QueryClientProvider>
    );
  }

  it("renders PGL-M3-02 rules and templates correctly", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Low stock: store keeper alert by SMS")).toBeTruthy();
      expect(screen.getByText("Price update: customer notice by SMS")).toBeTruthy();
      expect(screen.getByText("Birthday greeting: member SMS alert")).toBeTruthy();
    });

    expect(screen.getByText("low-stock-alert-sms")).toBeTruthy();
    expect(screen.getByText("price-update-sms")).toBeTruthy();
    expect(screen.getByText("birthday-greeting-sms")).toBeTruthy();
  });

  it("renders retry button for FAILED entries when user has int.notify.manage permission", async () => {
    state.permissions.add("int.notify.manage");
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Low-stock alert (SMS)")).toBeTruthy();
    });

    const retryButtons = screen.getAllByRole("button", { name: /retry/i });
    expect(retryButtons.length).toBeGreaterThan(0);

    fireEvent.click(retryButtons[0]);

    await waitFor(() => {
      expect(mockApi.retryNotification).toHaveBeenCalledWith(FAILED_ID, expect.any(String));
      expect(state.retriedIds).toContain(FAILED_ID);
    });
  });

  it("does not render retry button when user lacks int.notify.manage permission", async () => {
    // state.permissions is empty
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Low-stock alert (SMS)")).toBeTruthy();
    });

    const retryButtons = screen.queryAllByRole("button", { name: /retry delivery/i });
    expect(retryButtons).toHaveLength(0);
  });
});
