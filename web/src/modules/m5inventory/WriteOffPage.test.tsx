import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { WriteOff } from "./inventoryApi";
import { WriteOffPage } from "./WriteOffPage";

const LOCATION = "01a1c002-53ad-7c73-ab61-63780752eef9";
const WRITE_OFF = "01a1e8e8-2988-7df1-9f46-d12e933079cd";
const FED_ACCOUNTS = "0190f0de-0000-7000-8000-000000000205";

const mocks = vi.hoisted(() => ({
  useInventoryApi: vi.fn(),
  useApiClient: vi.fn(),
  writeOff: vi.fn(),
  writeOffPhoto: vi.fn(),
  writeOffStep: vi.fn(),
  getSession: vi.fn()
}));

vi.mock("./inventoryApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./inventoryApi")>();
  return { ...actual, useInventoryApi: mocks.useInventoryApi };
});

vi.mock("../../shell/api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../shell/api/client")>();
  return { ...actual, useApiClient: mocks.useApiClient };
});

vi.mock("../../shell/auth/session", () => ({
  useSession: () => ({
    userId: FED_ACCOUNTS,
    entityId: "0190f000-0000-7000-8000-000000000001",
    policyClass: "OWN",
    accessToken: "token",
    displayName: "Fathima Rizwan",
    locale: "en"
  })
}));

vi.mock("./SkuLabel", () => ({
  SkuLabel: ({ skuId }: { skuId: string }) => <span>{skuId}</span>
}));

const writeOff = {
  writeOffId: WRITE_OFF,
  locationId: LOCATION,
  category: "SPOILAGE",
  status: "REQUESTED",
  requestedBy: "0190f000-0000-7000-8000-0000000000a2",
  documentNo: "FED-FWTST01-WOF-0000001",
  value: 1100,
  band: 1,
  remoteWitness: false,
  photosRequired: true,
  lines: [
    {
      lineNo: 1,
      batchId: "0190f000-0000-7000-8000-000000000101",
      skuId: "0190f000-0000-7000-8000-000000000102",
      batchNo: "TEST-RICE-B001",
      condition: "GOOD",
      qty: 1
    }
  ],
  photos: [
    {
      attachmentId: "0190f000-0000-7000-8000-000000000103",
      status: "COMPLETE"
    }
  ]
} as WriteOff;

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/inventory/write-offs/${WRITE_OFF}`]}>
          <Routes>
            <Route path="/inventory/write-offs/:writeOffId" element={<WriteOffPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("WriteOffPage location-scoped actions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.writeOff.mockResolvedValue(writeOff);
    mocks.writeOffPhoto.mockResolvedValue(`${window.location.origin}/write-off-photo.jpg`);
    mocks.writeOffStep.mockResolvedValue({ ...writeOff, status: "WITNESSED" });
    mocks.getSession.mockResolvedValue({
      data: {
        userId: FED_ACCOUNTS,
        homeEntityId: "0190f000-0000-7000-8000-000000000001",
        policyClass: "OWN",
        activeScope: { entityId: "0190f000-0000-7000-8000-000000000001", locationId: LOCATION },
        scopes: [],
        grantedEntities: [],
        permissions: ["inv.stock.view", "inv.writeoff.witness", "inv.writeoff.approve"]
      }
    });
    mocks.useInventoryApi.mockImplementation((locationId?: string | null) =>
      locationId === undefined
        ? { writeOff: mocks.writeOff }
        : {
            writeOffStep: mocks.writeOffStep,
            writeOffPhoto: mocks.writeOffPhoto,
            addWriteOffPhoto: vi.fn(),
            rejectWriteOff: vi.fn()
          }
    );
    mocks.useApiClient.mockImplementation(() => ({ GET: mocks.getSession }));
  });

  afterEach(cleanup);

  it("shows and sends Witness in the document location when the entity-wide session lacks it", async () => {
    renderPage();

    const witness = await screen.findByRole("button", { name: "Witness" });
    expect((witness as HTMLButtonElement).disabled).toBe(false);
    expect(mocks.useInventoryApi).toHaveBeenCalledWith(LOCATION);
    expect(mocks.useApiClient).toHaveBeenCalledWith({ locationId: LOCATION });

    fireEvent.click(witness);

    await waitFor(() => expect(mocks.writeOffStep).toHaveBeenCalledWith(WRITE_OFF, "witness", expect.any(String)));
  });

  it("shows a verified photograph as a thumbnail and opens a larger preview", async () => {
    renderPage();

    const thumbnail = await screen.findByRole("button", { name: "View photograph 1" });
    expect(screen.getByText("1. Verified")).toBeTruthy();
    expect(mocks.writeOffPhoto).toHaveBeenCalledWith(WRITE_OFF, writeOff.photos[0].attachmentId);

    fireEvent.click(thumbnail);

    expect(screen.getByRole("dialog", { name: "Photograph 1 preview" })).toBeTruthy();
    expect(screen.getAllByAltText("Write-off photograph 1")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Close preview" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
