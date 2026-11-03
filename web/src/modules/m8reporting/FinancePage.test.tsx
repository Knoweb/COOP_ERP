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
  FinalAccountsReport,
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

const mockFinalAccounts: FinalAccountsReport = {
  period: "2026-11",
  currency: "LKR",
  status: "PARTIAL",
  statusReason:
    "Non-payroll operating expenses (rent, utilities) pending Phase 3 general ledger integration; total operating expenses and operating profit are unavailable",
  revenue: "150000.00",
  cogs: "100000.00",
  costPending: false,
  grossProfit: "50000.00",
  grossProfitMarginPercent: "33.33",
  payrollExpenses: "12500.00",
  otherExpenses: null,
  otherExpensesPending: true,
  totalExpenses: null,
  operatingProfit: null,
  operatingProfitMarginPercent: null,
  expenseCategories: [
    {
      category: "STAFF_PAYROLL",
      categoryName: "Staff & Payroll Costs",
      amount: "12500.00",
      status: "APPROVED",
      notes: "Approved biometric attendance and overtime contributions from M9 (1 contribution(s), 120 approved OT minutes)"
    },
    {
      category: "OTHER_OPERATING",
      categoryName: "Other Operating Expenses",
      amount: null,
      status: "UNAVAILABLE",
      notes: "Non-payroll operating expenses (rent, utilities, transport, maintenance) require general ledger / cashbook journal integration, which arrives in Phase 3"
    }
  ],
  payrollContributions: [
    {
      contributionId: "0190f8aa-1111-7000-8000-000000000001",
      employeeId: "0190f8bb-2222-7000-8000-000000000002",
      employeeNo: "EMP-001",
      employeeName: "Kamal Perera",
      contributionType: "OVERTIME",
      approvedAmount: "12500.00",
      approvedOtMinutes: 120,
      approvedAt: "2026-11-01T10:00:00Z",
      status: "APPROVED"
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
  })),
  getFinalAccounts: vi.fn(async () => mockFinalAccounts),
  getFinalAccountsExpenses: vi.fn(async () => ({
    period: "2026-11",
    currency: "LKR",
    totalPayrollExpenses: "12500.00",
    contributionCount: 1,
    categories: mockFinalAccounts.expenseCategories,
    contributions: mockFinalAccounts.payrollContributions
  })),
  exportFinalAccountsCsv: vi.fn(async () => "FINAL ACCOUNTS STATEMENT\nPeriod,2026-11\nRevenue,150000.00\n")
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

  it("switches to Final Accounts & Expenses tab and displays revenue, cogs, gross profit, approved payroll expenses, and drilldown", async () => {
    renderPage();

    const finalAccountsTab = screen.getByRole("tab", { name: "Final Accounts & Expenses" });
    fireEvent.click(finalAccountsTab);

    await waitFor(() => {
      expect(api.getFinalAccounts).toHaveBeenCalled();
      expect(screen.getByText(/Monthly Final Accounts Statement/)).toBeDefined();
      expect(screen.getByText("Sales Revenue")).toBeDefined();
      expect(screen.getByText("Cost of Goods Sold (COGS)")).toBeDefined();
      expect(screen.getByText("Gross Profit")).toBeDefined();
      expect(screen.getByText("Approved Payroll / Staff Costs")).toBeDefined();
      expect(screen.getByText("Operating Profit / Net Result")).toBeDefined();
      expect(screen.getByText("Staff & Payroll Costs")).toBeDefined();
      expect(screen.getByText("Other Operating Expenses")).toBeDefined();
      expect(screen.getByText("EMP-001")).toBeDefined();
      expect(screen.getByText("Kamal Perera")).toBeDefined();
      expect(screen.getByText("OVERTIME")).toBeDefined();
      expect(screen.getByText("120")).toBeDefined();
    });
  });

  it("handles period change and triggers CSV download for Final Accounts", async () => {
    renderPage();

    const finalAccountsTab = screen.getByRole("tab", { name: "Final Accounts & Expenses" });
    fireEvent.click(finalAccountsTab);

    await waitFor(() => {
      expect(api.getFinalAccounts).toHaveBeenCalled();
    });

    const monthInput = screen.getByLabelText(/Period/);
    fireEvent.change(monthInput, { target: { value: "2026-10" } });

    await waitFor(() => {
      expect(api.getFinalAccounts).toHaveBeenCalledWith("2026-10");
    });

    const downloadBtn = screen.getByRole("button", { name: "Download CSV" });
    fireEvent.click(downloadBtn);

    await waitFor(() => {
      expect(api.exportFinalAccountsCsv).toHaveBeenCalledWith("2026-10");
    });
  });
});
