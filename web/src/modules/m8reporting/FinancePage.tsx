import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { DatePicker } from "../../shell/components/DatePicker";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { openServerFile } from "../../shell/api/openServerFile";
import { useReportingApi } from "./reportingApi";
import "./reporting.css";

export function FinancePage({ initialTab = "exports" }: { initialTab?: "exports" | "goods" | "voucher" | "final-accounts" } = {}) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useReportingApi();

  const [activeTab, setActiveTab] = useState<"exports" | "goods" | "voucher" | "final-accounts">(initialTab);

  // Tab 1: Exports Filters
  const [asOfDate, setAsOfDate] = useState<string>(businessToday());
  const [downloadingDebtors, setDownloadingDebtors] = useState(false);
  const [downloadingCreditors, setDownloadingCreditors] = useState(false);
  const [downloadingOpening, setDownloadingOpening] = useState(false);

  // Tab 2: Goods Supplied Filters
  const [goodsFrom, setGoodsFrom] = useState<string>("");
  const [goodsTo, setGoodsTo] = useState<string>("");
  const [goodsSocietyId, setGoodsSocietyId] = useState<string>("");
  const [downloadingGoods, setDownloadingGoods] = useState(false);

  // Tab 3: Voucher Lookup
  const [voucherInvoiceIdInput, setVoucherInvoiceIdInput] = useState<string>("");
  const [selectedInvoiceId, setSelectedInvoiceId] = useState<string>("");
  const [printingVoucher, setPrintingVoucher] = useState(false);

  // Tab 4: Final Accounts Filters
  const [finalAccountsPeriod, setFinalAccountsPeriod] = useState<string>(() => businessToday().substring(0, 7));
  const [downloadingFinalAccounts, setDownloadingFinalAccounts] = useState(false);

  // Queries
  const debtorsQuery = useQuery({
    queryKey: ["reporting", "finance", "debtors", asOfDate],
    queryFn: () => api.listDebtorsReport(asOfDate || undefined),
    enabled: activeTab === "exports"
  });

  const creditorsQuery = useQuery({
    queryKey: ["reporting", "finance", "creditors", asOfDate],
    queryFn: () => api.listCreditorsReport(asOfDate || undefined),
    enabled: activeTab === "exports"
  });

  const openingQuery = useQuery({
    queryKey: ["reporting", "finance", "opening-balances"],
    queryFn: () => api.listOpeningBalancesReport(),
    enabled: activeTab === "exports"
  });

  const goodsQuery = useQuery({
    queryKey: ["reporting", "finance", "goods-supplied", goodsFrom, goodsTo, goodsSocietyId],
    queryFn: () =>
      api.getGoodsSuppliedReport({
        from: goodsFrom || undefined,
        to: goodsTo || undefined,
        societyId: goodsSocietyId || undefined
      }),
    enabled: activeTab === "goods"
  });

  const voucherQuery = useQuery({
    queryKey: ["reporting", "finance", "voucher", selectedInvoiceId],
    queryFn: () => api.getCreditVoucher(selectedInvoiceId),
    enabled: activeTab === "voucher" && Boolean(selectedInvoiceId)
  });

  const finalAccountsQuery = useQuery({
    queryKey: ["reporting", "finance", "final-accounts", finalAccountsPeriod],
    queryFn: () => api.getFinalAccounts(finalAccountsPeriod || undefined),
    enabled: activeTab === "final-accounts"
  });

  // Download handlers
  const handleDownloadDebtors = async () => {
    try {
      setDownloadingDebtors(true);
      const csv = await api.exportDebtorsCsv(asOfDate || undefined);
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `debtors_${asOfDate || businessToday()}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setDownloadingDebtors(false);
    }
  };

  const handleDownloadCreditors = async () => {
    try {
      setDownloadingCreditors(true);
      const csv = await api.exportCreditorsCsv(asOfDate || undefined);
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `creditors_${asOfDate || businessToday()}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setDownloadingCreditors(false);
    }
  };

  const handleDownloadOpening = async () => {
    try {
      setDownloadingOpening(true);
      const csv = await api.exportOpeningBalancesCsv();
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `opening_balances_${businessToday()}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setDownloadingOpening(false);
    }
  };

  const handleDownloadGoods = async () => {
    try {
      setDownloadingGoods(true);
      const csv = await api.exportGoodsSuppliedCsv({
        from: goodsFrom || undefined,
        to: goodsTo || undefined,
        societyId: goodsSocietyId || undefined
      });
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `goods_supplied_${goodsFrom || "start"}_${goodsTo || "today"}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setDownloadingGoods(false);
    }
  };

  const handlePrintVoucher = async () => {
    if (!selectedInvoiceId) return;
    setPrintingVoucher(true);
    try {
      await openServerFile(async () => {
        const res = await api.printCreditVoucher(selectedInvoiceId);
        return res.downloadUrl;
      });
    } finally {
      setPrintingVoucher(false);
    }
  };

  const handleDownloadFinalAccounts = async () => {
    try {
      setDownloadingFinalAccounts(true);
      const csv = await api.exportFinalAccountsCsv(finalAccountsPeriod || undefined);
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `final_accounts_${finalAccountsPeriod || "current"}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setDownloadingFinalAccounts(false);
    }
  };

  const debtors = debtorsQuery.data;
  const creditors = creditorsQuery.data;
  const opening = openingQuery.data;
  const goods = goodsQuery.data;
  const voucher = voucherQuery.data;
  const finalAccounts = finalAccountsQuery.data;

  return (
    <div className="reporting-page finance-page">
      <PageHeader
        icon={
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <line x1="12" y1="1" x2="12" y2="23" />
            <path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
          </svg>
        }
        title={t("reporting.finance.title").text}
      />
      <div className="finance-subtitle-banner">
        <span className="finance-subtitle-banner__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
        </span>
        <p className="finance-subtitle finance-subtitle-banner__text">
          {t("reporting.finance.subtitle").text}
        </p>
      </div>

      <div className="finance-tabs-wrapper">
        <div className="finance-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "exports"}
            className={`finance-tab ${activeTab === "exports" ? "finance-tab--active" : ""}`}
            onClick={() => setActiveTab("exports")}
          >
            <span className="finance-tab__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
            </span>
            <span className="finance-tab__label">{t("reporting.finance.tab_exports").text}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "goods"}
            className={`finance-tab ${activeTab === "goods" ? "finance-tab--active" : ""}`}
            onClick={() => setActiveTab("goods")}
          >
            <span className="finance-tab__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
                <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
                <line x1="12" y1="22.08" x2="12" y2="12" />
              </svg>
            </span>
            <span className="finance-tab__label">{t("reporting.finance.tab_goods").text}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "voucher"}
            className={`finance-tab ${activeTab === "voucher" ? "finance-tab--active" : ""}`}
            onClick={() => setActiveTab("voucher")}
          >
            <span className="finance-tab__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="16" y1="13" x2="8" y2="13" />
                <line x1="16" y1="17" x2="8" y2="17" />
                <polyline points="10 9 9 9 8 9" />
              </svg>
            </span>
            <span className="finance-tab__label">{t("reporting.finance.tab_voucher").text}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "final-accounts"}
            className={`finance-tab ${activeTab === "final-accounts" ? "finance-tab--active" : ""}`}
            onClick={() => setActiveTab("final-accounts")}
          >
            <span className="finance-tab__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="4" y="2" width="16" height="20" rx="2" />
                <line x1="8" y1="6" x2="16" y2="6" />
                <line x1="16" y1="14" x2="16" y2="18" />
                <path d="M8 10h.01" />
                <path d="M12 10h.01" />
                <path d="M16 10h.01" />
                <path d="M8 14h.01" />
                <path d="M12 14h.01" />
                <path d="M8 18h.01" />
                <path d="M12 18h.01" />
              </svg>
            </span>
            <span className="finance-tab__label">{t("reporting.finance.tab_final_accounts").text}</span>
          </button>
        </div>
      </div>

      {activeTab === "exports" && (
        <div className="finance-exports-tab finance-exports-container">
          {/* As Of Filter - Single Balanced Pill */}
          <div className="finance-as-of-filter-bar">
            <DatePicker
              value={asOfDate}
              onChange={setAsOfDate}
              prefixLabel={`${t("reporting.finance.as_of").text}:`}
              placeholder={businessToday()}
              className="finance-as-of-pill-picker"
            />
          </div>

          {/* Section 1: Debtors List */}
          <div className="finance-section-card">
            <div className="finance-section-header">
              <div className="finance-section-title-wrap">
                <span className="finance-section-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                    <circle cx="9" cy="7" r="4" />
                    <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                    <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                  </svg>
                </span>
                <h3>{t("reporting.finance.debtors_title").text}</h3>
              </div>
              <button
                type="button"
                className="finance-download-btn"
                disabled={downloadingDebtors}
                onClick={handleDownloadDebtors}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
                <span>{t("reporting.finance.download_csv").text}</span>
              </button>
            </div>

            {debtors && (
              <div className="finance-kpi-grid">
                <div className="finance-kpi-card finance-kpi-card--invoices">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                        <polyline points="14 2 14 8 20 8" />
                        <line x1="16" y1="13" x2="8" y2="13" />
                        <line x1="16" y1="17" x2="8" y2="17" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_open_inv").text}</span>
                  </div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={debtors.totalOpenInvoices} /></div>
                </div>
                <div className="finance-kpi-card finance-kpi-card--receipts">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_unapplied_rec").text}</span>
                  </div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={debtors.totalUnappliedReceipts} /></div>
                </div>
                <div className="finance-kpi-card finance-kpi-card--credits">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="2" y="5" width="20" height="14" rx="2" />
                        <line x1="2" y1="10" x2="22" y2="10" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_unapplied_cred").text}</span>
                  </div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={debtors.totalUnappliedCredits} /></div>
                </div>
                <div className="finance-kpi-card finance-kpi-card--highlight">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_net_exposure").text}</span>
                  </div>
                  <div className="finance-kpi-value finance-kpi-value--highlight"><MoneyDisplay amount={debtors.totalNetExposure} /></div>
                </div>
              </div>
            )}

            <div className="modern-table-card finance-table-card">
              <div className="modern-table-scroll">
                <table className="reporting-data-table finance-data-table">
                  <thead>
                    <tr>
                      <th>{t("reporting.finance.col_code").text}</th>
                      <th>{t("reporting.finance.col_debtor_name").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_credit_limit").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_open_invoices").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_unapplied_receipts").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_unapplied_credits").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_net_exposure").text}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {debtors?.items && debtors.items.length > 0 ? (
                      debtors.items.map((item) => (
                        <tr key={item.counterpartyId}>
                          <td className="finance-code-cell">{item.counterpartyCode || "—"}</td>
                          <td><strong>{item.counterpartyName}</strong></td>
                          <td className="finance-td-right">
                            {item.creditLimit ? <MoneyDisplay amount={item.creditLimit} /> : "—"}
                          </td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.openInvoices} /></td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.unappliedReceipts} /></td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.unappliedCredits} /></td>
                          <td className="finance-td-right finance-highlight-cell">
                            <strong><MoneyDisplay amount={item.netExposure} /></strong>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={7} className="finance-empty-cell">
                          {t("reporting.finance.no_debtors").text}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* Section 2: Creditors List */}
          <div className="finance-section-card">
            <div className="finance-section-header">
              <div className="finance-section-title-wrap">
                <span className="finance-section-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="2" y="3" width="20" height="14" rx="2" />
                    <line x1="8" y1="21" x2="16" y2="21" />
                    <line x1="12" y1="17" x2="12" y2="21" />
                  </svg>
                </span>
                <h3>{t("reporting.finance.creditors_title").text}</h3>
              </div>
              <button
                type="button"
                className="finance-download-btn"
                disabled={downloadingCreditors}
                onClick={handleDownloadCreditors}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
                <span>{t("reporting.finance.download_csv").text}</span>
              </button>
            </div>

            {creditors && (
              <div className="finance-kpi-grid">
                <div className="finance-kpi-card finance-kpi-card--invoices">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                        <polyline points="14 2 14 8 20 8" />
                        <line x1="16" y1="13" x2="8" y2="13" />
                        <line x1="16" y1="17" x2="8" y2="17" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_open_inv").text}</span>
                  </div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={creditors.totalOpenInvoices} /></div>
                </div>
                <div className="finance-kpi-card finance-kpi-card--receipts">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_unapplied_pay").text}</span>
                  </div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={creditors.totalUnappliedPayments} /></div>
                </div>
                <div className="finance-kpi-card finance-kpi-card--credits">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="2" y="5" width="20" height="14" rx="2" />
                        <line x1="2" y1="10" x2="22" y2="10" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_unapplied_cred").text}</span>
                  </div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={creditors.totalUnappliedCredits} /></div>
                </div>
                <div className="finance-kpi-card finance-kpi-card--highlight">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_net_liability").text}</span>
                  </div>
                  <div className="finance-kpi-value finance-kpi-value--highlight"><MoneyDisplay amount={creditors.totalNetLiability} /></div>
                </div>
              </div>
            )}

            <div className="modern-table-card finance-table-card">
              <div className="modern-table-scroll">
                <table className="reporting-data-table finance-data-table">
                  <thead>
                    <tr>
                      <th>{t("reporting.finance.col_code").text}</th>
                      <th>{t("reporting.finance.col_creditor_name").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_open_payables").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_accepted_not_invoiced").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_unapplied_payments").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_unapplied_credits").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_net_liability").text}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {creditors?.items && creditors.items.length > 0 ? (
                      creditors.items.map((item) => (
                        <tr key={item.counterpartyId}>
                          <td className="finance-code-cell">{item.counterpartyCode || "—"}</td>
                          <td><strong>{item.counterpartyName}</strong></td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.openInvoices} /></td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.acceptedNotInvoiced} /></td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.unappliedPayments} /></td>
                          <td className="finance-td-right"><MoneyDisplay amount={item.unappliedCredits} /></td>
                          <td className="finance-td-right finance-highlight-cell">
                            <strong><MoneyDisplay amount={item.netLiability} /></strong>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={7} className="finance-empty-cell">
                          {t("reporting.finance.no_creditors").text}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* Section 3: Opening Balances */}
          <div className="finance-section-card">
            <div className="finance-section-header">
              <div className="finance-section-title-wrap">
                <span className="finance-section-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3" y="4" width="18" height="16" rx="2" />
                    <line x1="16" y1="2" x2="16" y2="6" />
                    <line x1="8" y1="2" x2="8" y2="6" />
                    <line x1="3" y1="10" x2="21" y2="10" />
                  </svg>
                </span>
                <h3>{t("reporting.finance.opening_title").text}</h3>
              </div>
              <button
                type="button"
                className="finance-download-btn"
                disabled={downloadingOpening}
                onClick={handleDownloadOpening}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
                <span>{t("reporting.finance.download_csv").text}</span>
              </button>
            </div>

            {opening && (
              <div className="finance-kpi-grid">
                <div className="finance-kpi-card finance-kpi-card--highlight">
                  <div className="finance-kpi-header">
                    <span className="finance-kpi-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="3" y="4" width="18" height="16" rx="2" />
                        <line x1="16" y1="2" x2="16" y2="6" />
                        <line x1="8" y1="2" x2="8" y2="6" />
                      </svg>
                    </span>
                    <span className="finance-kpi-label">{t("reporting.finance.total_opening_amount").text}</span>
                  </div>
                  <div className="finance-kpi-value finance-kpi-value--highlight"><MoneyDisplay amount={opening.totalAmount} /></div>
                </div>
              </div>
            )}

            <div className="modern-table-card finance-table-card">
              <div className="modern-table-scroll">
                <table className="reporting-data-table finance-data-table">
                  <thead>
                    <tr>
                      <th>{t("reporting.finance.col_doc_no").text}</th>
                      <th>{t("reporting.finance.col_counterparty").text}</th>
                      <th>{t("reporting.finance.col_role").text}</th>
                      <th className="finance-th-right">{t("reporting.finance.col_amount").text}</th>
                      <th>{t("reporting.finance.col_reference").text}</th>
                      <th>{t("reporting.finance.col_date").text}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {opening?.items && opening.items.length > 0 ? (
                      opening.items.map((item) => (
                        <tr key={item.openingReceivableId}>
                          <td className="finance-code-cell">{item.docNumberDisplay}</td>
                          <td>{item.counterpartyName} ({item.counterpartyCode || item.counterpartyId.substring(0, 8)})</td>
                          <td>
                            <span className="finance-badge-role">
                              {item.role}
                            </span>
                          </td>
                          <td className="finance-td-right finance-highlight-cell">
                            <strong><MoneyDisplay amount={item.amount} /></strong>
                          </td>
                          <td>{item.referenceNo || "—"}</td>
                          <td>{formatDate(item.createdAt)}</td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={6} className="finance-empty-cell">
                          {t("reporting.finance.no_opening").text}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* Section 4: Bank Balances (Declared BLOCKED/Unavailable in V1 per prompt) */}
          <div className="finance-section-card finance-section-card--blocked">
            <div className="finance-section-header">
              <div className="finance-section-title-wrap">
                <span className="finance-section-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                  </svg>
                </span>
                <h3>
                  {t("reporting.finance.bank_title").text}
                  <span className="finance-badge-phase3">
                    {t("reporting.finance.phase_3").text}
                  </span>
                </h3>
              </div>
            </div>
            <p className="finance-blocked-desc">
              {t("reporting.finance.bank_blocked_desc").text}
            </p>
          </div>
        </div>
      )}

      {activeTab === "goods" && (
        <div className="goods-supplied-tab finance-goods-tab">
          {/* Filters */}
          <div className="filters-bar finance-goods-filters">
            <div className="finance-filter-field">
              <span className="finance-filter-field-label">{t("reporting.finance.from_date").text}:</span>
              <DatePicker
                value={goodsFrom}
                onChange={setGoodsFrom}
                placeholder="YYYY-MM-DD"
                className="finance-compact-datepicker"
              />
            </div>
            <div className="finance-filter-field">
              <span className="finance-filter-field-label">{t("reporting.finance.to_date").text}:</span>
              <DatePicker
                value={goodsTo}
                onChange={setGoodsTo}
                placeholder="YYYY-MM-DD"
                className="finance-compact-datepicker"
              />
            </div>
            <div className="finance-filter-field">
              <span className="finance-filter-field-label">{t("reporting.finance.society_id").text}:</span>
              <input
                type="text"
                placeholder={t("reporting.finance.placeholder_optional_uuid").text}
                value={goodsSocietyId}
                onChange={(e) => setGoodsSocietyId(e.target.value)}
                className="finance-goods-society-input"
              />
            </div>
            <button
              type="button"
              className="finance-download-btn finance-goods-download-btn"
              disabled={downloadingGoods}
              onClick={handleDownloadGoods}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
              <span>{t("reporting.finance.download_csv").text}</span>
            </button>
          </div>

          {/* Goods Summary KPI */}
          {goods && (
            <div className="finance-kpi-grid">
              <div className="kpi-card finance-kpi-card">
                <div className="finance-kpi-label">{t("reporting.finance.line_count").text}</div>
                <div className="finance-kpi-value">{goods.lineCount}</div>
              </div>
              <div className="kpi-card finance-kpi-card">
                <div className="finance-kpi-label">{t("reporting.finance.total_qty").text}</div>
                <div className="finance-kpi-value">{goods.totalQuantity}</div>
              </div>
              <div className="kpi-card finance-kpi-card">
                <div className="finance-kpi-label">{t("reporting.finance.total_net").text}</div>
                <div className="finance-kpi-value"><MoneyDisplay amount={goods.totalNetAmount} /></div>
              </div>
              <div className="kpi-card finance-kpi-card">
                <div className="finance-kpi-label">{t("reporting.finance.total_tax").text}</div>
                <div className="finance-kpi-value"><MoneyDisplay amount={goods.totalTaxAmount} /></div>
              </div>
              <div className="kpi-card finance-kpi-card highlight">
                <div className="finance-kpi-label">{t("reporting.finance.total_gross").text}</div>
                <div className="finance-kpi-value brand"><MoneyDisplay amount={goods.totalGrossAmount} /></div>
              </div>
            </div>
          )}

          {/* Detailed Lines Table */}
          <div className="card finance-section-card">
            <div className="table-responsive finance-table-responsive">
              <table className="table finance-table">
                <thead>
                  <tr>
                    <th>{t("reporting.finance.col_invoice_no").text}</th>
                    <th>{t("reporting.finance.col_date").text}</th>
                    <th>{t("reporting.finance.col_society").text}</th>
                    <th>{t("reporting.finance.col_sku_code").text}</th>
                    <th>{t("reporting.finance.col_product_name").text}</th>
                    <th>{t("reporting.finance.col_uom").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_quantity").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_unit_price").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_tax_pct").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_line_total").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {goods?.lines && goods.lines.length > 0 ? (
                    goods.lines.map((l, idx) => (
                      <tr key={`${l.invoiceId}-${l.skuId}-${idx}`}>
                        <td className="finance-code-cell">{l.invoiceDocNumber}</td>
                        <td>{formatDate(l.invoiceDate)}</td>
                        <td>{l.societyName}</td>
                        <td>{l.skuCode}</td>
                        <td>{l.skuName}</td>
                        <td>{l.uomCode}</td>
                        <td className="finance-td-right">{l.quantity}</td>
                        <td className="finance-td-right"><MoneyDisplay amount={l.unitPrice} /></td>
                        <td className="finance-td-right">{l.taxRatePercent}%</td>
                        <td className="finance-td-right"><strong><MoneyDisplay amount={l.lineTotal} /></strong></td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={10} className="finance-empty-cell">
                        {t("reporting.finance.no_goods").text}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {activeTab === "voucher" && (
        <div className="credit-voucher-tab finance-voucher-tab">
          {/* Voucher Lookup Input */}
          <div className="card finance-section-card">
            <h3>{t("reporting.finance.voucher_lookup").text}</h3>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                setSelectedInvoiceId(voucherInvoiceIdInput.trim());
              }}
              className="finance-voucher-search-bar"
            >
              <label>
                <span>{t("reporting.finance.invoice_id_label").text}</span>
                <input
                  type="text"
                  value={voucherInvoiceIdInput}
                  onChange={(e) => setVoucherInvoiceIdInput(e.target.value)}
                  placeholder={t("reporting.finance.placeholder_voucher_uuid").text}
                  className="input"
                  required
                />
              </label>
              <button type="submit" className="btn btn-primary">
                {t("reporting.finance.lookup_btn").text}
              </button>
            </form>
          </div>

          {/* Voucher Document Card Preview */}
          {voucher && (
            <div className="card voucher-preview finance-voucher-box">
              <div className="finance-voucher-header">
                <div>
                  <h2>{t("reporting.finance.voucher_details").text}</h2>
                  <div className="finance-voucher-doc-id">
                    {voucher.voucherNumber}
                  </div>
                  <div className="finance-voucher-date-ref">
                    {t("reporting.finance.label_date").text} {voucher.date} | {t("reporting.finance.label_ref").text} {voucher.sourceTransactionReference}
                  </div>
                </div>
                <div>
                  <button
                    type="button"
                    className="btn btn-primary finance-btn-margin"
                    disabled={printingVoucher}
                    onClick={handlePrintVoucher}
                  >
                    {t("reporting.finance.print_btn").text}
                  </button>
                  <div>
                    {voucher.status && (
                      <StateChip state={(voucher.status.toLowerCase() as "alert" | "draft" | "issued" | "disputed" | "void") || "issued"} />
                    )}
                  </div>
                </div>
              </div>

              <div className="finance-voucher-parties">
                <div className="finance-party-card">
                  <div className="finance-party-title">
                    {t("reporting.finance.seller").text}
                  </div>
                  <div className="finance-party-name">{voucher.sellerName}</div>
                  {voucher.sellerCode && <div className="finance-party-sub">{t("reporting.finance.label_code").text} {voucher.sellerCode}</div>}
                  {voucher.sellerVatNo && <div className="finance-party-sub">{t("reporting.finance.label_vat").text} {voucher.sellerVatNo}</div>}
                </div>
                <div className="finance-party-card">
                  <div className="finance-party-title">
                    {t("reporting.finance.buyer").text}
                  </div>
                  <div className="finance-party-name">{voucher.buyerName}</div>
                  {voucher.buyerCode && <div className="finance-party-sub">{t("reporting.finance.label_code").text} {voucher.buyerCode}</div>}
                  {voucher.buyerVatNo && <div className="finance-party-sub">{t("reporting.finance.label_vat").text} {voucher.buyerVatNo}</div>}
                </div>
              </div>

              {/* Voucher Item Lines */}
              <table className="table finance-voucher-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>{t("reporting.finance.col_sku_code").text}</th>
                    <th>{t("reporting.finance.col_description").text}</th>
                    <th>{t("reporting.finance.col_uom").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_quantity").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_unit_price").text}</th>
                    <th className="finance-th-right">{t("reporting.finance.col_line_total").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {voucher.lines.map((l) => (
                    <tr key={l.lineNo}>
                      <td>{l.lineNo}</td>
                      <td>{l.skuCode}</td>
                      <td><strong>{l.skuName}</strong></td>
                      <td>{l.uomCode}</td>
                      <td className="finance-td-right">{l.quantity}</td>
                      <td className="finance-td-right"><MoneyDisplay amount={l.unitPrice} /></td>
                      <td className="finance-td-right"><strong><MoneyDisplay amount={l.lineTotal} /></strong></td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="finance-voucher-totals-wrapper">
                <div className="finance-voucher-totals-box">
                  <div className="finance-total-row">
                    <span>{t("reporting.finance.label_net_amount").text}</span>
                    <span><strong><MoneyDisplay amount={voucher.netAmount} /></strong></span>
                  </div>
                  <div className="finance-total-row">
                    <span>{t("reporting.finance.label_vat_amount").text}</span>
                    <span><strong><MoneyDisplay amount={voucher.taxAmount} /></strong></span>
                  </div>
                  <div className="finance-total-row-grand">
                    <span>{t("reporting.finance.label_total_voucher").text}</span>
                    <span><MoneyDisplay amount={voucher.grossAmount} /></span>
                  </div>
                </div>
              </div>
            </div>
          )}

          {!voucher && !voucherQuery.isLoading && (
            <div className="finance-placeholder-box">
              {t("reporting.finance.enter_invoice").text}
            </div>
          )}
        </div>
      )}

      {activeTab === "final-accounts" && (
        <div className="final-accounts-tab finance-final-accounts-container">
          {/* Period Filter & Download */}
          <div className="filters-bar finance-filter-bar">
            <span className="finance-filter-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
                <line x1="16" y1="2" x2="16" y2="6" />
                <line x1="8" y1="2" x2="8" y2="6" />
                <line x1="3" y1="10" x2="21" y2="10" />
              </svg>
            </span>
            <label className="finance-filter-label">
              <span>{t("reporting.finance.period").text}:</span>
              <input
                type="month"
                value={finalAccountsPeriod}
                onChange={(e) => setFinalAccountsPeriod(e.target.value)}
                className="finance-date-input"
              />
            </label>
            <button
              type="button"
              className="finance-download-btn"
              disabled={downloadingFinalAccounts}
              onClick={handleDownloadFinalAccounts}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
              <span>{t("reporting.finance.download_csv").text}</span>
            </button>
          </div>

          {finalAccountsQuery.isLoading && (
            <div className="finance-placeholder-box">
              {t("reporting.loading").text}
            </div>
          )}

          {finalAccounts && (
            <>
              {/* Section 1: Final Accounts Statement Summary */}
              <div className="finance-section card finance-section-card">
                <div className="finance-section-header">
                  <div>
                    <h3>{t("reporting.finance.final_accounts_title").text} — {finalAccounts.period}</h3>
                    {finalAccounts.statusReason && (
                      <p className="finance-subtitle finance-status-subtitle">
                        {finalAccounts.statusReason}
                      </p>
                    )}
                  </div>
                  <div>
                    <StateChip
                      state={finalAccounts.status === "COMPLETE" ? "issued" : "draft"}
                    />
                  </div>
                </div>

                <div className="finance-kpi-grid">
                  <div className="kpi-card finance-kpi-card">
                    <div className="finance-kpi-label">{t("reporting.finance.revenue").text}</div>
                    <div className="finance-kpi-value"><MoneyDisplay amount={finalAccounts.revenue} /></div>
                  </div>
                  <div className="kpi-card finance-kpi-card">
                    <div className="finance-kpi-label">{t("reporting.finance.cogs").text}</div>
                    <div className="finance-kpi-value">
                      {finalAccounts.cogs ? (
                        <MoneyDisplay amount={finalAccounts.cogs} />
                      ) : (
                        <span className="finance-badge-phase3">{t("reporting.finance.pending").text}</span>
                      )}
                    </div>
                  </div>
                  <div className="kpi-card finance-kpi-card highlight">
                    <div className="finance-kpi-label">{t("reporting.finance.gross_profit").text}</div>
                    <div className="finance-kpi-value brand">
                      {finalAccounts.grossProfit ? (
                        <>
                          <MoneyDisplay amount={finalAccounts.grossProfit} />
                          {finalAccounts.grossProfitMarginPercent && (
                            <span className="finance-margin-percent">
                              ({finalAccounts.grossProfitMarginPercent}%)
                            </span>
                          )}
                        </>
                      ) : (
                        <span className="finance-badge-phase3">{t("reporting.finance.pending").text}</span>
                      )}
                    </div>
                  </div>
                  <div className="kpi-card finance-kpi-card">
                    <div className="finance-kpi-label">{t("reporting.finance.payroll_expenses").text}</div>
                    <div className="finance-kpi-value"><MoneyDisplay amount={finalAccounts.payrollExpenses} /></div>
                  </div>
                  <div className="kpi-card finance-kpi-card">
                    <div className="finance-kpi-label">{t("reporting.finance.total_expenses").text}</div>
                    <div className="finance-kpi-value">
                      {finalAccounts.totalExpenses ? (
                        <MoneyDisplay amount={finalAccounts.totalExpenses} />
                      ) : (
                        <span className="finance-badge-phase3">{t("reporting.finance.pending").text}</span>
                      )}
                    </div>
                  </div>
                  <div className="kpi-card finance-kpi-card highlight">
                    <div className="finance-kpi-label">{t("reporting.finance.operating_profit").text}</div>
                    <div className="finance-kpi-value brand">
                      {finalAccounts.operatingProfit ? (
                        <>
                          <MoneyDisplay amount={finalAccounts.operatingProfit} />
                          {finalAccounts.operatingProfitMarginPercent && (
                            <span className="finance-margin-percent">
                              ({finalAccounts.operatingProfitMarginPercent}%)
                            </span>
                          )}
                        </>
                      ) : (
                        <span className="finance-badge-phase3">{t("reporting.finance.pending").text}</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>

              {/* Section 2: Operating Expense Classification */}
              <div className="finance-section card finance-section-card">
                <div className="finance-section-header">
                  <h3>{t("reporting.finance.expense_breakdown").text}</h3>
                </div>
                <div className="table-responsive finance-table-responsive">
                  <table className="table finance-table">
                    <thead>
                      <tr>
                        <th>{t("reporting.finance.col_category").text}</th>
                        <th>{t("reporting.finance.col_category_name").text}</th>
                        <th className="finance-th-right">{t("reporting.finance.col_amount").text}</th>
                        <th>{t("reporting.finance.col_status").text}</th>
                        <th>{t("reporting.finance.col_notes").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {finalAccounts.expenseCategories.map((cat) => (
                        <tr key={cat.category}>
                          <td className="finance-code-cell">{cat.category}</td>
                          <td><strong>{cat.categoryName}</strong></td>
                          <td className="finance-td-right">
                            {cat.amount ? <MoneyDisplay amount={cat.amount} /> : "—"}
                          </td>
                          <td>
                            <span className={cat.status === "APPROVED" ? "finance-badge-role" : "finance-badge-phase3"}>
                              {cat.status}
                            </span>
                          </td>
                          <td className="finance-notes-cell">{cat.notes || "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Section 3: Approved Payroll Contributions Drilldown */}
              <div className="finance-section card finance-section-card">
                <div className="finance-section-header">
                  <h3>{t("reporting.finance.payroll_contributions_title").text}</h3>
                </div>
                <div className="table-responsive finance-table-responsive">
                  <table className="table finance-table">
                    <thead>
                      <tr>
                        <th>{t("reporting.finance.col_emp_no").text}</th>
                        <th>{t("reporting.finance.col_emp_name").text}</th>
                        <th>{t("reporting.finance.col_contrib_type").text}</th>
                        <th className="finance-th-right">{t("reporting.finance.col_ot_minutes").text}</th>
                        <th className="finance-th-right">{t("reporting.finance.col_approved_amount").text}</th>
                        <th>{t("reporting.finance.col_date").text}</th>
                        <th>{t("reporting.finance.col_status").text}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {finalAccounts.payrollContributions && finalAccounts.payrollContributions.length > 0 ? (
                        finalAccounts.payrollContributions.map((item) => (
                          <tr key={item.contributionId}>
                            <td className="finance-code-cell">{item.employeeNo}</td>
                            <td><strong>{item.employeeName}</strong></td>
                            <td><span className="finance-badge-role">{item.contributionType}</span></td>
                            <td className="finance-td-right">{item.approvedOtMinutes}</td>
                            <td className="finance-td-right"><strong><MoneyDisplay amount={item.approvedAmount} /></strong></td>
                            <td>{item.approvedAt ? formatDate(item.approvedAt.substring(0, 10)) : "—"}</td>
                            <td><StateChip state="issued" /></td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={7} className="finance-empty-cell">
                            {t("reporting.finance.no_payroll_contributions").text}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}