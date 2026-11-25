import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { LabelJob } from "./pricingApi";
import { ShelfLabelPrintAction } from "./ShelfLabelPrintAction";

const SHOP = "0190e760-0000-7000-8000-000000000105";
const SKU = "0190e760-0000-7000-8000-000000000205";
const JOB = "0190e760-0000-7000-8000-000000000305";
const state = { canPrint: true };

const rendered: LabelJob = {
  jobId: JOB,
  locationId: SHOP,
  status: "RENDERED",
  requestedCount: 1,
  renderedCount: 0,
  refused: [
    {
      skuId: SKU,
      skuCode: "RICE-01",
      reason: "ABOVE_CONTROL_PRICE",
      listPrice: 240,
      ceilingPrice: 220,
      ceilingReference: "2492/29"
    }
  ],
  downloadUrl: `${window.location.origin}/reports/labels.pdf`,
  createdAt: "2026-10-21T06:00:00Z"
};

const api = {
  locations: vi.fn(async () => [
    { locationId: SHOP, ownerEntityId: "entity", locationCode: "S01", locationType: "SHOP" as const, nameEn: "Main shop" }
  ]),
  printLabels: vi.fn(async (): Promise<LabelJob> => ({ ...rendered, status: "QUEUED", refused: [], downloadUrl: undefined })),
  labelJob: vi.fn(async (): Promise<LabelJob> => rendered)
};

vi.mock("./pricingApi", () => ({ usePricingApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: () => state.canPrint }));

function renderAction() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <ShelfLabelPrintAction skuIds={[SKU]} />
      </IntlProvider>
    </QueryClientProvider>
  );
}

const text = (id: string) => (messages.en as Record<string, string>)[id];

describe("shelf-label printing", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.canPrint = true;
  });
  afterEach(cleanup);

  it("is hidden without prc.label.print", () => {
    state.canPrint = false;
    renderAction();

    expect(screen.queryByRole("heading", { name: text("pricing.labels.title") })).toBeNull();
    expect(api.locations).not.toHaveBeenCalled();
  });

  it("queues the shelf list, polls the result and shows labels refused above the control price", async () => {
    const tab = { location: { href: "" }, close: vi.fn() };
    const open = vi.spyOn(window, "open").mockReturnValue(tab as unknown as Window);
    renderAction();

    await screen.findByRole("option", { name: "Main shop" });
    fireEvent.change(await screen.findByLabelText(text("pricing.labels.shop")), { target: { value: SHOP } });
    const print = screen.getByRole("button", { name: text("pricing.labels.print") });
    await waitFor(() => expect(print.hasAttribute("disabled")).toBe(false));
    fireEvent.click(print);

    await waitFor(() => expect(api.printLabels).toHaveBeenCalledWith(SHOP, [SKU], expect.any(String)));
    expect(await screen.findByText("RICE-01")).toBeTruthy();
    expect(screen.getByText(text("pricing.labels.reason.above_control_price"))).toBeTruthy();
    expect(screen.getByText(/2492\/29/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: text("pricing.labels.open") }));
    await waitFor(() => expect(tab.location.href).toBe(`${window.location.origin}/reports/labels.pdf`));
    expect(open).toHaveBeenCalledOnce();
    open.mockRestore();
  });
});
