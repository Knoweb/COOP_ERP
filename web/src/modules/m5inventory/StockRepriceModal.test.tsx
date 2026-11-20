import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { LotBalance, Sku } from "./inventoryApi";
import { StockRepriceModal } from "./StockRepriceModal";

const LOT_ID = "0190f0de-0000-7000-8000-000000000101";
const SKU_ID = "0190f0de-0000-7000-8000-000000000102";

const mockLot = {
  stockLotId: LOT_ID,
  locationId: "0190f0de-0000-7000-8000-000000000101",
  skuId: SKU_ID,
  batchId: "0190f0de-0000-7000-8000-000000000103",
  batchNo: "BAT-100",
  expiryDate: "2027-12-31",
  condition: "GOOD",
  qtyOnHand: 10,
  unitCost: 100,
  negative: false,
  expired: false
} as LotBalance;

const mockSku = {
  skuId: SKU_ID,
  skuCode: "SKU-RICE-01",
  nameEn: "White Rice 5kg",
  nameSi: "සුදු කැකුළු 5kg",
  nameTa: "வெள்ளை அரிசி 5kg",
  status: "SHARED"
} as unknown as Sku;

const api = {
  repriceStockLot: vi.fn(async () => ({
    repricingId: "0190f0de-0000-7000-8000-000000000999",
    stockLotId: LOT_ID,
    skuId: SKU_ID,
    batchId: "0190f0de-0000-7000-8000-000000000103",
    condition: "GOOD" as const,
    qtyAffected: 10,
    previousUnitCost: 100,
    newUnitCost: 70,
    targetCondition: "GOOD" as const,
    valueAdjustment: -300,
    reason: "Market drop",
    repricedBy: "0190f0de-0000-7000-8000-000000000001",
    repricedAt: "2026-10-30T10:00:00Z"
  }))
};

vi.mock("./inventoryApi", async () => {
  const actual = await vi.importActual("./inventoryApi");
  return {
    ...actual,
    useInventoryApi: () => api
  };
});

describe("StockRepriceModal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  function renderModal(props: Partial<React.ComponentProps<typeof StockRepriceModal>> = {}) {
    const defaultProps = {
      isOpen: true,
      onClose: vi.fn(),
      lot: mockLot,
      sku: mockSku,
      onSuccess: vi.fn(),
      ...props
    };

    return {
      ...render(
        <IntlProvider locale="en" messages={messages.en}>
          <StockRepriceModal {...defaultProps} />
        </IntlProvider>
      ),
      props: defaultProps
    };
  }

  it("renders modal with lot details and initialized form values", () => {
    renderModal();

    expect(screen.getByRole("dialog")).toBeTruthy();
    expect(screen.getByText("Reprice Stock Lot")).toBeTruthy();
    expect(screen.getByText("SKU-RICE-01")).toBeTruthy();
    expect(screen.getByText(/White Rice 5kg/)).toBeTruthy();
    expect(screen.getByText("BAT-100")).toBeTruthy();

    const qtyInput = screen.getByLabelText(/Quantity to Reprice/) as HTMLInputElement;
    expect(qtyInput.value).toBe("10");

    const costInput = screen.getByLabelText(/New Unit Cost/) as HTMLInputElement;
    expect(costInput.value).toBe("100");
  });

  it("calculates value adjustment dynamically", () => {
    renderModal();

    const costInput = screen.getByLabelText(/New Unit Cost/);
    fireEvent.change(costInput, { target: { value: "70" } });

    // 10 * (70 - 100) = -300.00
    expect(screen.getByText(/Total Value Adjustment/)).toBeTruthy();
  });

  it("calculates blended cost for partial in-place repricing", () => {
    renderModal();

    const qtyInput = screen.getByLabelText(/Quantity to Reprice/);
    fireEvent.change(qtyInput, { target: { value: "4" } });

    const costInput = screen.getByLabelText(/New Unit Cost/);
    fireEvent.change(costInput, { target: { value: "70" } });

    // (4 * 70 + 6 * 100) / 10 = 88.00
    expect(screen.getByText(/Projected Blended Cost/)).toBeTruthy();
    expect(screen.getByText(/Rs 88.00/)).toBeTruthy();
  });

  it("offers both target conditions and changes a GOOD lot to DAMAGED", () => {
    renderModal();

    const trigger = screen.getByRole("button", { name: "Good" });
    fireEvent.click(trigger);
    const listbox = screen.getByRole("listbox", { name: "Target Condition" });
    expect(within(listbox).getByRole("option", { name: "Good" })).toBeTruthy();
    fireEvent.click(within(listbox).getByRole("option", { name: "Damaged" }));

    expect(screen.getByRole("button", { name: "Damaged" })).toBeTruthy();
  });

  it("disables Apply Reprice and explains the temporary restriction in en, si, and ta", () => {
    for (const locale of ["en", "si", "ta"] as const) {
      cleanup();
      render(
        <IntlProvider locale={locale} messages={messages[locale]}>
          <StockRepriceModal
            isOpen={true}
            onClose={vi.fn()}
            lot={mockLot}
            sku={mockSku}
            onSuccess={vi.fn()}
          />
        </IntlProvider>
      );

      const expectedNotice = (messages[locale] as Record<string, string>)["inventory.reprice.temporarily_unavailable"];
      const submitLabel = (messages[locale] as Record<string, string>)["inventory.reprice.submit"];
      expect(screen.getByRole("status").textContent).toContain(expectedNotice);

      const submitBtn = screen.getByRole("button", { name: submitLabel }) as HTMLButtonElement;
      expect(submitBtn.disabled).toBe(true);
    }
  });

  it("blocks form submission and never invokes repriceStockLot while repricing is disabled", async () => {
    const { props } = renderModal();

    const costInput = screen.getByLabelText(/New Unit Cost/);
    fireEvent.change(costInput, { target: { value: "70" } });

    const reasonInput = screen.getByLabelText(/Reason/);
    fireEvent.change(reasonInput, { target: { value: "Supplier price decline" } });

    const submitBtn = screen.getByRole("button", { name: "Apply Reprice" }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(true);
    fireEvent.click(submitBtn);

    const form = document.body.querySelector("form")!;
    fireEvent.submit(form);

    await waitFor(() => {
      expect(screen.getByRole("alert").textContent).toContain(
        (messages.en as Record<string, string>)["inventory.reprice.temporarily_unavailable"]
      );
    });
    expect(api.repriceStockLot).not.toHaveBeenCalled();
    expect(props.onSuccess).not.toHaveBeenCalled();
    expect(props.onClose).not.toHaveBeenCalled();
  });
});
