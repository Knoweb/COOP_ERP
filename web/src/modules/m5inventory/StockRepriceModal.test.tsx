import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  it("validates empty reason and prevents submission", async () => {
    renderModal();

    const submitBtn = screen.getByRole("button", { name: "Apply Reprice" });
    fireEvent.click(submitBtn);

    expect(api.repriceStockLot).not.toHaveBeenCalled();
  });

  it("successfully calls repriceStockLot on valid form submission", async () => {
    const { props } = renderModal();

    const costInput = screen.getByLabelText(/New Unit Cost/);
    fireEvent.change(costInput, { target: { value: "70" } });

    const reasonInput = screen.getByLabelText(/Reason/);
    fireEvent.change(reasonInput, { target: { value: "Supplier price decline" } });

    const submitBtn = screen.getByRole("button", { name: "Apply Reprice" });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(api.repriceStockLot).toHaveBeenCalledWith(
        LOT_ID,
        {
          qty: 10,
          newUnitCost: 70,
          targetCondition: "GOOD",
          reason: "Supplier price decline"
        },
        expect.any(String)
      );
      expect(props.onSuccess).toHaveBeenCalled();
      expect(props.onClose).toHaveBeenCalled();
    });
  });

  it("displays server error message when api call fails", async () => {
    api.repriceStockLot.mockRejectedValueOnce(new Error("m5.reprice.qty_exceeded"));
    renderModal();

    const reasonInput = screen.getByLabelText(/Reason/);
    fireEvent.change(reasonInput, { target: { value: "Price adjustment" } });

    const submitBtn = screen.getByRole("button", { name: "Apply Reprice" });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeTruthy();
    });
  });
});
