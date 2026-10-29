import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { useInventoryApi } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { lossCategories, unacknowledgedNegativeLotCount } from "./inventoryScreens";
import { errorText } from "./stockView";
import "./inventory.css";

export function LossShrinkagePage() {
  const t = useT();
  const api = useInventoryApi();
  const [locationId, setLocationId] = useState("");
  const writeOffs = useQuery({ queryKey: ["inventory", "loss", "write-offs", locationId], queryFn: () => api.writeOffs(locationId), enabled: locationId !== "" });
  const negatives = useQuery({ queryKey: ["inventory", "loss", "negative", locationId], queryFn: () => api.negativeLots(locationId), enabled: locationId !== "" });
  const summary = lossCategories(writeOffs.data ?? []);
  const negativeLotCount = unacknowledgedNegativeLotCount(negatives.data ?? []);
  const failed = writeOffs.error ?? negatives.error;

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <PageHeader icon="stock" title={t("inventory.loss.title").text} />
      <p className="modern-intro-text">{t("inventory.loss.intro").text}</p>
      <section className="modern-filter-panel modern-filter-panel--stock"><LocationPicker value={locationId} onChange={setLocationId} /></section>
      {(writeOffs.isLoading || negatives.isLoading) && <p>{t("inventory.loading").text}</p>}
      {failed && <p role="alert">{errorText(failed, t("inventory.error.generic").text)}</p>}
      {locationId !== "" && !failed && (
        <>
          <section className="inventory-metric-grid" aria-label={t("inventory.loss.summary").text}>
            <article className="modern-table-card inventory-metric"><span>{t("inventory.loss.posted_value").text}</span><strong><MoneyDisplay amount={summary.reduce((total, row) => total + row.value, 0)} /></strong></article>
            <article className="modern-table-card inventory-metric"><span>{t("inventory.loss.documents").text}</span><strong>{summary.reduce((total, row) => total + row.documents, 0)}</strong></article>
            <article className="modern-table-card inventory-metric"><span>{t("inventory.loss.negative_lots").text}</span><strong>{negativeLotCount}</strong></article>
          </section>
          {summary.length === 0 ? <p>{t("inventory.loss.none").text}</p> : (
            <section className="modern-table-card"><div className="modern-table-scroll"><table className="modern-table">
              <thead><tr><th>{t("inventory.writeoff.category").text}</th><th>{t("inventory.loss.documents").text}</th><th>{t("inventory.writeoff.value").text}</th></tr></thead>
              <tbody>{summary.map((row) => <tr key={row.category}><td>{t(`inventory.writeoff.category.${row.category}`).text}</td><td className="numeric-cell">{row.documents}</td><td className="numeric-cell"><MoneyDisplay amount={row.value} /></td></tr>)}</tbody>
            </table></div></section>
          )}
        </>
      )}
    </main>
  );
}
