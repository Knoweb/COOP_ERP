import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { expiryRows } from "./inventoryScreens";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";

function colomboToday(): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Colombo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(new Date());
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? "";
  return `${value("year")}-${value("month")}-${value("day")}`;
}

export function ExpiryListPage() {
  const t = useT();
  const api = useInventoryApi();
  const formatDate = useFormatDate();
  const [locationId, setLocationId] = useState("");
  const balances = useQuery({
    queryKey: ["inventory", "expiry", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });
  const rows = expiryRows(balances.data ?? [], colomboToday());

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <PageHeader icon="stock" title={t("inventory.expiry.title").text} />
      <p className="modern-intro-text">{t("inventory.expiry.intro").text}</p>
      <section className="modern-filter-panel modern-filter-panel--stock"><LocationPicker value={locationId} onChange={setLocationId} /></section>
      {balances.isLoading && <p>{t("inventory.loading").text}</p>}
      {balances.isError && <p role="alert">{errorText(balances.error, t("inventory.error.generic").text)}</p>}
      {locationId !== "" && !balances.isLoading && rows.length === 0 && <p>{t("inventory.expiry.none").text}</p>}
      {rows.length > 0 && (
        <section className="modern-table-card">
          <div className="modern-table-scroll"><table className="modern-table stock-table">
            <thead><tr><th>{t("inventory.column.item").text}</th><th>{t("inventory.column.batch").text}</th><th>{t("inventory.column.expiry").text}</th><th>{t("inventory.expiry.days").text}</th><th>{t("inventory.column.on_hand").text}</th><th>{t("inventory.repack.column.status").text}</th></tr></thead>
            <tbody>{rows.map((row) => (
              <tr key={row.stockLotId}>
                <td><SkuLabel skuId={row.skuId} /></td>
                <td>{isSyntheticBatchNo(row.batchNo) ? t("inventory.field.batch_not_tracked").text : row.batchNo}</td>
                <td>{formatDate(row.expiryDate!)}</td>
                <td className="numeric-cell">{row.daysRemaining}</td>
                <td className="numeric-cell">{row.qtyOnHand}</td>
                <td><StateChip state={row.expired ? "void" : "issued"} label={t(row.expired ? "inventory.expiry.expired" : "inventory.expiry.monitor").text} /></td>
              </tr>
            ))}</tbody>
          </table></div>
        </section>
      )}
    </main>
  );
}
