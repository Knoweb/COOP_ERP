import { type FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi, type Batch, type PickLine } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function PickListPage() {
  const t = useT();
  const api = useInventoryApi();
  const formatDate = useFormatDate();
  const [entry, setEntry] = useState("");
  const [deliveryNoteId, setDeliveryNoteId] = useState("");
  const valid = UUID.test(entry.trim());
  const pick = useQuery({
    queryKey: ["inventory", "pick-list", deliveryNoteId],
    queryFn: () => api.pickList(deliveryNoteId),
    enabled: deliveryNoteId !== "",
    retry: false
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (valid) setDeliveryNoteId(entry.trim());
  };
  const batchIds = [...new Set((pick.data?.lines ?? []).flatMap((line: PickLine) => line.batchId ? [line.batchId] : []))];
  const batches = useQuery({
    queryKey: ["inventory", "pick-list", "batches", batchIds],
    queryFn: async () => {
      const found = (await Promise.all(batchIds.map((id) => api.batch(id)))).filter((batch): batch is Batch => batch !== null);
      return new Map<string, Batch>(found.map((batch) => [batch.batchId, batch]));
    },
    enabled: batchIds.length > 0
  });

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <PageHeader icon="stock" title={t("inventory.pick.title").text} />
      <p className="modern-intro-text">{t("inventory.pick.intro").text}</p>
      <form className="modern-filter-panel inventory-form-row" onSubmit={submit}>
        <label className="inventory-form-field">
          {t("inventory.pick.delivery_note").text}
          <input value={entry} onChange={(event) => setEntry(event.target.value)} aria-invalid={entry !== "" && !valid} />
        </label>
        {entry !== "" && !valid && <p role="alert">{t("inventory.pick.invalid_id").text}</p>}
        <button type="submit" disabled={!valid}>{t("inventory.pick.open").text}</button>
      </form>
      {pick.isLoading && <p>{t("inventory.loading").text}</p>}
      {pick.isError && <p role="alert">{errorText(pick.error, t("inventory.pick.not_found").text)}</p>}
      {batches.isError && <p role="alert">{errorText(batches.error, t("inventory.error.generic").text)}</p>}
      {pick.data && (
        <section className="modern-table-card inventory-section">
          <div className="inventory-card-heading">
            <h2>{t("inventory.pick.list").text}</h2>
            <StateChip state={pick.data.status === "OPEN" ? "alert" : "issued"} label={t(`inventory.pick.status.${pick.data.status}`).text} />
          </div>
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead><tr><th>{t("inventory.column.item").text}</th><th>{t("inventory.column.batch").text}</th><th>{t("inventory.column.expiry").text}</th><th>{t("inventory.column.qty").text}</th><th>{t("inventory.pick.result").text}</th></tr></thead>
              <tbody>{pick.data.lines.map((line: PickLine, index: number) => (
                <tr key={`${line.deliveryLineId}-${line.stockLotId ?? index}`}>
                  <td><SkuLabel skuId={line.skuId} /></td>
                  <td>{line.batchId ? (() => {
                    const batch = batches.data?.get(line.batchId);
                    if (!batch) return t("inventory.loading").text;
                    return isSyntheticBatchNo(batch.batchNo) ? t("inventory.field.batch_not_tracked").text : batch.batchNo;
                  })() : t("inventory.pick.short").text}</td>
                  <td>{line.batchId && batches.data?.get(line.batchId)?.expiryDate ? formatDate(batches.data.get(line.batchId)!.expiryDate!) : ""}</td>
                  <td className="numeric-cell">{line.qty}</td>
                  <td>{line.stockLotId ? t("inventory.pick.allocated").text : t("inventory.pick.short").text}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}
