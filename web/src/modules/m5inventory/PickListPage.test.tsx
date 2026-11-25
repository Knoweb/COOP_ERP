import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { PickListPage } from "./PickListPage";

const DELIVERY = "01900000-0000-7000-8000-000000000001";
const PICK_LINE = "01900000-0000-7000-8000-000000000002";
const DELIVERY_LINE = "01900000-0000-7000-8000-000000000003";
const SKU = "01900000-0000-7000-8000-000000000004";
const LOCATION = "01900000-0000-7000-8000-000000000005";
const ORIGINAL_LOT = "01900000-0000-7000-8000-000000000006";
const ORIGINAL_BATCH = "01900000-0000-7000-8000-000000000007";
const REPLACEMENT_LOT = "01900000-0000-7000-8000-000000000008";
const REPLACEMENT_BATCH = "01900000-0000-7000-8000-000000000009";

const api = {
  pickList: vi.fn(async () => ({
    pickListId: "01900000-0000-7000-8000-000000000010",
    deliveryNoteId: DELIVERY,
    status: "OPEN" as const,
    lines: [{
      pickLineId: PICK_LINE,
      deliveryLineId: DELIVERY_LINE,
      skuId: SKU,
      locationId: LOCATION,
      stockLotId: ORIGINAL_LOT,
      batchId: ORIGINAL_BATCH,
      qty: 5
    }]
  })),
  batch: vi.fn(async (batchId: string) => ({ batchId, batchNo: "ORIGINAL", skuId: SKU })),
  pickReplacementCandidates: vi.fn(async () => [
    {
      stockLotId: REPLACEMENT_LOT,
      batchId: REPLACEMENT_BATCH,
      batchNo: "REPLACEMENT",
      availableQty: 8
    }
  ]),
  overridePick: vi.fn(async () => undefined)
};

vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));
vi.mock("./SkuLabel", () => ({ SkuLabel: ({ skuId }: { skuId: string }) => <span>{skuId}</span> }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter>
          <PickListPage />
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

async function openOverride() {
  fireEvent.change(screen.getByRole("textbox", { name: text("inventory.pick.delivery_note") }), {
    target: { value: DELIVERY }
  });
  fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.open") }));
  await screen.findByRole("table");
  fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.override.open") }));
  const form = await screen.findByRole("dialog", { name: text("inventory.pick.override.title") });
  await screen.findByRole("combobox", { name: text("inventory.pick.override.replacement") });
  return form;
}

describe("the pick-list override", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("keeps the reason mandatory and submits an eligible replacement with an idempotency key", async () => {
    renderPage();
    const form = await openOverride();
    const replacement = screen.getByRole("combobox", { name: text("inventory.pick.override.replacement") });
    fireEvent.change(replacement, { target: { value: REPLACEMENT_LOT } });
    expect(screen.getByRole("button", { name: text("inventory.pick.override.confirm") }).hasAttribute("disabled")).toBe(true);

    fireEvent.change(screen.getByRole("textbox", { name: text("inventory.pick.override.reason") }), {
      target: { value: "Earlier batch is damaged" }
    });
    fireEvent.submit(form);

    await waitFor(() =>
      expect(api.overridePick).toHaveBeenCalledWith(
        DELIVERY,
        PICK_LINE,
        { replacementStockLotId: REPLACEMENT_LOT, reason: "Earlier batch is damaged" },
        expect.any(String)
      )
    );
    expect((await screen.findByRole("status")).textContent).toContain(text("inventory.pick.override.success"));
    await waitFor(() => expect(api.pickList.mock.calls.length).toBeGreaterThan(1));
  });

  it("shows an API failure and leaves the override form available for correction", async () => {
    api.overridePick.mockRejectedValueOnce(new Error("network"));
    renderPage();
    await openOverride();
    fireEvent.change(screen.getByRole("combobox", { name: text("inventory.pick.override.replacement") }), {
      target: { value: REPLACEMENT_LOT }
    });
    fireEvent.change(screen.getByRole("textbox", { name: text("inventory.pick.override.reason") }), {
      target: { value: "Use the later lot" }
    });
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.override.confirm") }));

    expect((await screen.findByRole("alert")).textContent).toContain(text("inventory.error.generic"));
    expect(screen.getByRole("dialog", { name: text("inventory.pick.override.title") })).not.toBeNull();
  });

  it("shows candidate loading failures and retries the whs.pick candidate read", async () => {
    api.pickReplacementCandidates.mockRejectedValueOnce(new Error("network"));
    renderPage();

    fireEvent.change(screen.getByRole("textbox", { name: text("inventory.pick.delivery_note") }), {
      target: { value: DELIVERY }
    });
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.open") }));
    await screen.findByRole("table");
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.override.open") }));

    expect((await screen.findByRole("alert")).textContent).toContain(text("inventory.error.generic"));
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.override.retry") }));
    await screen.findByRole("combobox", { name: text("inventory.pick.override.replacement") });
    expect(api.pickReplacementCandidates).toHaveBeenCalledTimes(2);
  });

  it("shows explicit loading and empty states for the candidate read", async () => {
    api.pickReplacementCandidates.mockImplementationOnce(() => new Promise(() => undefined));
    renderPage();
    fireEvent.change(screen.getByRole("textbox", { name: text("inventory.pick.delivery_note") }), {
      target: { value: DELIVERY }
    });
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.open") }));
    await screen.findByRole("table");
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.override.open") }));
    const loadingDialog = await screen.findByRole("dialog", { name: text("inventory.pick.override.title") });
    expect(within(loadingDialog).getByText(text("inventory.loading"))).not.toBeNull();
    cleanup();

    api.pickReplacementCandidates.mockResolvedValueOnce([]);
    renderPage();
    fireEvent.change(screen.getByRole("textbox", { name: text("inventory.pick.delivery_note") }), {
      target: { value: DELIVERY }
    });
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.open") }));
    await screen.findByRole("table");
    fireEvent.click(screen.getByRole("button", { name: text("inventory.pick.override.open") }));
    expect(await screen.findByText(text("inventory.pick.override.none"))).not.toBeNull();
  });
});
