import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { openServerFile } from "../../shell/api/openServerFile";
import { useReportingApi } from "./reportingApi";
import "./reporting.css";

export function FinancePage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useReportingApi();

  const [activeTab, setActiveTab] = useState<"exports" | "goods" | "voucher">("exports");

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

  const debtors = debtorsQuery.data;
  const creditors = creditorsQuery.data;
  const opening = openingQuery.data;
  const goods = goodsQuery.data;
  const voucher = voucherQuery.data;

  return (
    <div className="reporting-page finance-page">
      <PageHeader
        icon="report"
        title={t("reporting.finance.title").text}
      />
      <p className="finance-subtitle">
        {t("reporting.finance.subtitle").text}
      </p>

      <div className="reporting-tabs finance-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "exports"}
          className={`btn ${activeTab === "exports" ? "btn-primary" : "btn-secondary"}`}
          onClick={() => setActiveTab("exports")}
        >
          {t("reporting.finance.tab_exports").text}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "goods"}
          className={`btn ${activeTab === "goods" ? "btn-primary" : "btn-secondary"}`}
          onClick={() => setActiveTab("goods")}
        >
          {t("reporting.finance.tab_goods").text}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === "voucher"}
          className={`btn ${activeTab === "voucher" ? "btn-primary" : "btn-secondary"}`}
          onClick={() => setActiveTab("voucher")}
        >
          {t("reporting.finance.tab_voucher").text}
        </button>
      </div>

      {activeTab === "exports" && (
        <div className="finance-exports-tab finance-exports-container">
          {/* As Of Filter */}
          <div className="filters-bar finance-filter-bar">
            <label>
              {t("reporting.finance.as_of").text}:
              <input
                type="date"
                value={asOfDate}
                onChange={(e) => setAsOfDate(e.target.value)}
                className="input"
              />
            </label>
          </div>

          {/* Section 1: Debtors List */}
          <div className="finance-section card finance-section-card">
            <div className="finance-section-header">
              <h3>{t("reporting.finance.debtors_title").text}</h3>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={downloadingDebtors}
                onClick={handleDownloadDebtors}
              >
                {t("reporting.finance.download_csv").text}
              </button>
            </div>

            {debtors && (
              <div className="finance-kpi-grid">
                <div className="kpi-card finance-kpi-card">
                  <div className="finance-kpi-label">{t("reporting.finance.total_open_inv").text}</div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={debtors.totalOpenInvoices} /></div>
                </div>
                <div className="kpi-card finance-kpi-card">
                  <div className="finance-kpi-label">{t("reporting.finance.total_unapplied_rec").text}</div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={debtors.totalUnappliedReceipts} /></div>
                </div>
                <div className="kpi-card finance-kpi-card">
                  <div className="finance-kpi-label">{t("reporting.finance.total_unapplied_cred").text}</div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={debtors.totalUnappliedCredits} /></div>
                </div>
                <div className="kpi-card finance-kpi-card highlight">
                  <div className="finance-kpi-label">{t("reporting.finance.total_net_exposure").text}</div>
                  <div className="finance-kpi-value brand"><MoneyDisplay amount={debtors.totalNetExposure} /></div>
                </div>
              </div>
            )}

            <div className="table-responsive finance-table-responsive">
              <table className="table finance-table">
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
                        <td>{item.counterpartyName}</td>
                        <td className="finance-td-right">
                          {item.creditLimit ? <MoneyDisplay amount={item.creditLimit} /> : "—"}
                        </td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.openInvoices} /></td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.unappliedReceipts} /></td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.unappliedCredits} /></td>
                        <td className="finance-td-right"><strong><MoneyDisplay amount={item.netExposure} /></strong></td>
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

          {/* Section 2: Creditors List */}
          <div className="finance-section card finance-section-card">
            <div className="finance-section-header">
              <h3>{t("reporting.finance.creditors_title").text}</h3>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={downloadingCreditors}
                onClick={handleDownloadCreditors}
              >
                {t("reporting.finance.download_csv").text}
              </button>
            </div>

            {creditors && (
              <div className="finance-kpi-grid">
                <div className="kpi-card finance-kpi-card">
                  <div className="finance-kpi-label">{t("reporting.finance.total_open_inv").text}</div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={creditors.totalOpenInvoices} /></div>
                </div>
                <div className="kpi-card finance-kpi-card">
                  <div className="finance-kpi-label">{t("reporting.finance.total_unapplied_pay").text}</div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={creditors.totalUnappliedPayments} /></div>
                </div>
                <div className="kpi-card finance-kpi-card">
                  <div className="finance-kpi-label">{t("reporting.finance.total_unapplied_cred").text}</div>
                  <div className="finance-kpi-value"><MoneyDisplay amount={creditors.totalUnappliedCredits} /></div>
                </div>
                <div className="kpi-card finance-kpi-card highlight">
                  <div className="finance-kpi-label">{t("reporting.finance.total_net_liability").text}</div>
                  <div className="finance-kpi-value brand"><MoneyDisplay amount={creditors.totalNetLiability} /></div>
                </div>
              </div>
            )}

            <div className="table-responsive finance-table-responsive">
              <table className="table finance-table">
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
                        <td>{item.counterpartyName}</td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.openInvoices} /></td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.acceptedNotInvoiced} /></td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.unappliedPayments} /></td>
                        <td className="finance-td-right"><MoneyDisplay amount={item.unappliedCredits} /></td>
                        <td className="finance-td-right"><strong><MoneyDisplay amount={item.netLiability} /></strong></td>
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

          {/* Section 3: Opening Balances */}
          <div className="finance-section card finance-section-card">
            <div className="finance-section-header">
              <h3>{t("reporting.finance.opening_title").text}</h3>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={downloadingOpening}
                onClick={handleDownloadOpening}
              >
                {t("reporting.finance.download_csv").text}
              </button>
            </div>

            {opening && (
              <div className="finance-kpi-grid">
                <div className="kpi-card finance-kpi-card highlight">
                  <div className="finance-kpi-label">{t("reporting.finance.total_opening_amount").text}</div>
                  <div className="finance-kpi-value brand"><MoneyDisplay amount={opening.totalAmount} /></div>
                </div>
              </div>
            )}

            <div className="table-responsive finance-table-responsive">
              <table className="table finance-table">
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
                        <td className="finance-td-right"><strong><MoneyDisplay amount={item.amount} /></strong></td>
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

          {/* Section 4: Bank Balances (Declared BLOCKED/Unavailable in V1 per prompt) */}
          <div className="finance-section card finance-section-card blocked">
            <div className="finance-section-header">
              <h3>
                {t("reporting.finance.bank_title").text}
                <span className="finance-badge-phase3">
                  {t("reporting.finance.phase_3").text}
                </span>
              </h3>
            </div>
            <p className="finance-subtitle">
              {t("reporting.finance.bank_blocked_desc").text}
            </p>
          </div>
        </div>
      )}

      {activeTab === "goods" && (
        <div className="goods-supplied-tab finance-goods-tab">
          {/* Filters */}
          <div className="filters-bar finance-goods-filters">
            <label>
              {t("reporting.finance.from_date").text}:
              <input
                type="date"
                value={goodsFrom}
                onChange={(e) => setGoodsFrom(e.target.value)}
                className="input"
              />
            </label>
            <label>
              {t("reporting.finance.to_date").text}:
              <input
                type="date"
                value={goodsTo}
                onChange={(e) => setGoodsTo(e.target.value)}
                className="input"
              />
            </label>
            <label>
              <span>{t("reporting.finance.society_id").text}</span>
              <input
                type="text"
                placeholder={t("reporting.finance.placeholder_optional_uuid").text}
                value={goodsSocietyId}
                onChange={(e) => setGoodsSocietyId(e.target.value)}
                className="input finance-goods-society-input"
              />
            </label>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={downloadingGoods}
              onClick={handleDownloadGoods}
            >
              {t("reporting.finance.download_csv").text}
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
                    {t("reporting.finance.label_date").text} {formatDate(voucher.date)} | {t("reporting.finance.label_ref").text} {voucher.sourceTransactionReference}
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
    </div>
  );
}
