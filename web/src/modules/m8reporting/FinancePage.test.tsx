import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type {
  CreditVoucher,
  CreditorsReport,
  DebtorsReport,
  GoodsSuppliedReport,
  OpeningBalancesReport
} from "./reportingApi";
import { FinancePage } from "./FinancePage";

const mockDebtors: DebtorsReport = {
  asOf: "2026-10-30",
  totalOpenInvoices: "150000.00",
  totalUnappliedReceipts: "10000.00",
  totalUnappliedCredits: "5000.00",
  totalNetExposure: "135000.00",
  items: [
    {
      counterpartyId: "0190f8dd-0000-7000-8000-000000000001",
      counterpartyCode: "SOC-01",
      counterpartyName: "Polgolla MPCS",
      creditLimit: "200000.00",
      openInvoices: "150000.00",
      acceptedNotInvoiced: "20000.00",
      unappliedReceipts: "10000.00",
      unappliedCredits: "5000.00",
      netExposure: "135000.00",
      warnThresholdPercent: 75
    }
  ]
};

const mockCreditors: CreditorsReport = {
  asOf: "2026-10-30",
  totalOpenInvoices: "250000.00",
  totalUnappliedPayments: "20000.00",
  totalUnappliedCredits: "10000.00",
  totalNetLiability: "220000.00",
  items: [
    {
      counterpartyId: "0190f8dd-0000-7000-8000-000000000002",
      counterpartyCode: "FED-01",
      counterpartyName: "Sri Lanka Coop Federation",
      openInvoices: "250000.00",
      acceptedNotInvoiced: "0.00",
      unappliedPayments: "20000.00",
      unappliedCredits: "10000.00",
      netLiability: "220000.00"
    }
  ]
};

const mockOpening: OpeningBalancesReport = {
  totalAmount: "75000.00",
  items: [
    {
      openingReceivableId: "0190f8dd-0000-7000-8000-000000000003",
      docNumberDisplay: "OPR-2026-00001",
      counterpartyId: "0190f8dd-0000-7000-8000-000000000001",
      counterpartyCode: "SOC-01",
      counterpartyName: "Polgolla MPCS",
      role: "SELLER",
      amount: "75000.00",
      referenceNo: "OLD-REF-99",
      notes: "Migrated ledger",
      signedAt: "2026-10-01T10:00:00Z",
      createdAt: "2026-10-01T09:00:00Z"
    }
  ]
};

const mockGoodsSupplied: GoodsSuppliedReport = {
  from: "2026-10-01",
  to: "2026-10-30",
  lineCount: 1,
  totalQuantity: "50.000",
  totalNetAmount: "25000.00",
  totalTaxAmount: "2000.00",
  totalGrossAmount: "27000.00",
  lines: [
    {
      invoiceId: "0190f8aa-0000-7000-8000-000000000001",
      invoiceDocNumber: "INV-2026-00042",
      invoiceDate: "2026-10-15",
      societyEntityId: "0190f8dd-0000-7000-8000-000000000001",
      societyName: "Polgolla MPCS",
      skuId: "0190f8ee-0000-7000-8000-000000000001",
      skuCode: "SKU-RICE",
      skuName: "Samba Rice 5kg",
      uomCode: "BAG",
      quantity: "50.000",
      unitPrice: "500.00",
      taxRatePercent: "8.00",
      taxAmount: "2000.00",
      lineTotal: "27000.00"
    }
  ]
};

const mockCreditVoucher: CreditVoucher = {
  voucherNumber: "CPV-INV-2026-00042",
  invoiceId: "0190f8aa-0000-7000-8000-000000000001",
  invoiceDocNumber: "INV-2026-00042",
  date: "2026-10-15",
  sellerEntityId: "0190f8dd-0000-7000-8000-000000000002",
  sellerName: "Sri Lanka Coop Federation",
  sellerCode: "FED-01",
  sellerVatNo: "VAT-FED-1234",
  buyerEntityId: "0190f8dd-0000-7000-8000-000000000001",
  buyerName: "Polgolla MPCS",
  buyerCode: "SOC-01",
  buyerVatNo: "VAT-POL-5678",
  sourceTransactionReference: "INV-2026-00042",
  netAmount: "25000.00",
  taxAmount: "2000.00",
  grossAmount: "27000.00",
  status: "ISSUED",
  lines: [
    {
      lineNo: 1,
      skuId: "0190f8ee-0000-7000-8000-000000000001",
      skuCode: "SKU-RICE",
      skuName: "Samba Rice 5kg",
      uomCode: "BAG",
      quantity: "50.000",
      unitPrice: "500.00",
      taxRatePercent: "8.00",
      taxAmount: "2000.00",
      lineTotal: "27000.00"
    }
  ]
};

const api = {
  listDebtorsReport: vi.fn(async () => mockDebtors),
  exportDebtorsCsv: vi.fn(async () => "Code,Debtor Name,Credit Limit\nSOC-01,Polgolla MPCS,200000.00\n"),
  listCreditorsReport: vi.fn(async () => mockCreditors),
  exportCreditorsCsv: vi.fn(async () => "Code,Creditor Name,Open Payables\nFED-01,Federation,250000.00\n"),
  listOpeningBalancesReport: vi.fn(async () => mockOpening),
  exportOpeningBalancesCsv: vi.fn(async () => "Document No,Counterparty,Role\nOPR-2026-00001,Polgolla,SELLER\n"),
  getGoodsSuppliedReport: vi.fn(async () => mockGoodsSupplied),
  exportGoodsSuppliedCsv: vi.fn(async () => "Invoice No,Date,Society\nINV-2026-00042,2026-10-15,Polgolla\n"),
  getCreditVoucher: vi.fn(async () => mockCreditVoucher),
  printCreditVoucher: vi.fn(async () => ({
    invoiceId: "0190f8aa-0000-7000-8000-000000000001",
    voucherNumber: "CPV-INV-2026-00042",
    downloadUrl: "http://localhost:9000/reports/test/voucher.pdf"
  }))
};

vi.mock("./reportingApi", async () => {
  const actual = await vi.importActual<object>("./reportingApi");
  return {
    ...actual,
    useReportingApi: () => api
  };
});

vi.mock("../../shell/api/openServerFile", () => ({
  openServerFile: vi.fn(async (getUrl: () => Promise<string>) => {
    await getUrl();
  })
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/reporting/finance"]}>
          <Routes>
            <Route path="/reporting/finance" element={<FinancePage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("FinancePage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders Finance Exports tab with debtors, creditors, opening balances, and bank balances card", async () => {
    renderPage();

    expect(screen.getByText("Finance Exports, Goods Summary & Vouchers")).toBeDefined();
    expect(screen.getByRole("tab", { name: "Finance Exports" })).toBeDefined();
    expect(screen.getByRole("tab", { name: "Goods Supplied" })).toBeDefined();
    expect(screen.getByRole("tab", { name: "Credit Voucher" })).toBeDefined();

    // Verify Debtors Section
    await waitFor(() => {
      expect(screen.getByText("Debtor List (Receivables)")).toBeDefined();
      expect(screen.getByText("Polgolla MPCS")).toBeDefined();
      expect(screen.getByText("SOC-01")).toBeDefined();
    });

    // Verify Creditors Section
    expect(screen.getByText("Creditor List (Payables)")).toBeDefined();
    expect(screen.getByText("Sri Lanka Coop Federation")).toBeDefined();

    // Verify Opening Balances Section
    expect(screen.getByText("Opening Balances List")).toBeDefined();
    expect(screen.getByText("OPR-2026-00001")).toBeDefined();

    // Verify Bank Balances Blocked/Phase 3 Section
    expect(screen.getByText("Bank Balances")).toBeDefined();
    expect(screen.getByText("Phase 3")).toBeDefined();
  });

  it("switches to Goods Supplied tab and shows aggregated facts and lines", async () => {
    renderPage();

    const goodsTab = screen.getByRole("tab", { name: "Goods Supplied" });
    fireEvent.click(goodsTab);

    await waitFor(() => {
      expect(api.getGoodsSuppliedReport).toHaveBeenCalled();
      expect(screen.getByText("INV-2026-00042")).toBeDefined();
      expect(screen.getByText("Samba Rice 5kg")).toBeDefined();
      expect(screen.getByText("SKU-RICE")).toBeDefined();
    });
  });

  it("switches to Credit Voucher tab, searches for an invoice, shows voucher preview, and triggers print", async () => {
    renderPage();

    const voucherTab = screen.getByRole("tab", { name: "Credit Voucher" });
    fireEvent.click(voucherTab);

    const input = screen.getByPlaceholderText("e.g. 018d9f5a-3c2b-7e4a-9b1c-...");
    fireEvent.change(input, { target: { value: "0190f8aa-0000-7000-8000-000000000001" } });

    const lookupBtn = screen.getByRole("button", { name: "Lookup Voucher" });
    fireEvent.click(lookupBtn);

    await waitFor(() => {
      expect(api.getCreditVoucher).toHaveBeenCalledWith("0190f8aa-0000-7000-8000-000000000001");
      expect(screen.getByText("CPV-INV-2026-00042")).toBeDefined();
      expect(screen.getByText(/VAT-FED-1234/)).toBeDefined();
      expect(screen.getByText(/VAT-POL-5678/)).toBeDefined();
    });

    const printBtn = screen.getByRole("button", { name: "Print A4 Voucher" });
    fireEvent.click(printBtn);

    await waitFor(() => {
      expect(api.printCreditVoucher).toHaveBeenCalledWith("0190f8aa-0000-7000-8000-000000000001");
    });
  });
});
