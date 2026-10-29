import { type FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { type PickList, useInventoryApi } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";
import { type Batch, type PickList, useInventoryApi } from "./inventoryApi";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
type PickLine = PickList["lines"][number];

function PickOverrideForm({ deliveryNoteId, line, onClose }: { deliveryNoteId: string; line: PickLine; onClose: () => void }) {
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [replacementStockLotId, setReplacementStockLotId] = useState("");
  const [reason, setReason] = useState("");
  const [completed, setCompleted] = useState(false);
  const candidates = useQuery({
    queryKey: ["inventory", "pick-list", deliveryNoteId, "replacement-candidates", line.pickLineId],
    queryFn: () => api.pickReplacementCandidates(deliveryNoteId, line.pickLineId),
    enabled: line.locationId != null,
    retry: false
  });
  const override = useMutation({
    mutationFn: () =>
      api.overridePick(
        deliveryNoteId,
        line.pickLineId,
        { replacementStockLotId, reason: reason.trim() },
        key.current()
      ),
    onSuccess: async () => {
      key.next();
      setCompleted(true);
      await queryClient.invalidateQueries({ queryKey: ["inventory", "pick-list", deliveryNoteId] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) key.next();
    }
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (replacementStockLotId !== "" && reason.trim() !== "") override.mutate();
  };

  if (completed) {
    return (
      <section className="inventory-pick-override" role="status">
        <p>{t("inventory.pick.override.success").text}</p>
        <button type="button" onClick={onClose}>{t("inventory.pick.override.close").text}</button>
      </section>
    );
  }

  return (
    <form className="inventory-pick-override" role="dialog" aria-labelledby="pick-override-title" onSubmit={submit}>
      <h3 id="pick-override-title">{t("inventory.pick.override.title").text}</h3>
      <p>{t("inventory.pick.override.intro").text}</p>
      {candidates.isLoading && <p>{t("inventory.loading").text}</p>}
      {candidates.isError && (
        <div>
          <p role="alert">{errorText(candidates.error, t("inventory.error.generic").text)}</p>
          <button type="button" onClick={() => void candidates.refetch()}>
            {t("inventory.pick.override.retry").text}
          </button>
        </div>
      )}
      {!candidates.isLoading && !candidates.isError && candidates.data?.length === 0 && (
        <p>{t("inventory.pick.override.none").text}</p>
      )}
      {(candidates.data?.length ?? 0) > 0 && (
        <label className="inventory-form-field">
          {t("inventory.pick.override.replacement").text}
          <select value={replacementStockLotId} onChange={(event) => setReplacementStockLotId(event.target.value)} required>
            <option value="">{t("inventory.pick.override.choose").text}</option>
            {candidates.data!.map((candidate) => (
              <option key={candidate.stockLotId} value={candidate.stockLotId}>
                {candidate.batchNo ?? t("inventory.field.batch_not_tracked").text} · {t("inventory.pick.override.available", undefined, { qty: candidate.availableQty }).text}
              </option>
            ))}
          </select>
        </label>
      )}
      <label className="inventory-form-field">
        {t("inventory.pick.override.reason").text}
        <textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} rows={3} required />
      </label>
      {override.isError && <p role="alert">{errorText(override.error, t("inventory.error.generic").text)}</p>}
      <div className="inventory-action-bar">
        <button type="submit" disabled={override.isPending || replacementStockLotId === "" || reason.trim() === ""}>
          {t("inventory.pick.override.confirm").text}
        </button>
        <button type="button" onClick={onClose} disabled={override.isPending}>{t("inventory.pick.override.cancel").text}</button>
      </div>
    </form>
  );
}

export function PickListPage() {
  const t = useT();
  const api = useInventoryApi();
  const formatDate = useFormatDate();
  const [entry, setEntry] = useState("");
  const [deliveryNoteId, setDeliveryNoteId] = useState("");
  const [overriding, setOverriding] = useState<PickLine | null>(null);
  const valid = UUID.test(entry.trim());
  const pick = useQuery({
    queryKey: ["inventory", "pick-list", deliveryNoteId],
    queryFn: () => api.pickList(deliveryNoteId),
    enabled: deliveryNoteId !== "",
    retry: false
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (valid) {
      setOverriding(null);
      setDeliveryNoteId(entry.trim());
    }
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
              <thead><tr><th>{t("inventory.column.item").text}</th><th>{t("inventory.column.batch").text}</th><th>{t("inventory.column.expiry").text}</th><th>{t("inventory.column.qty").text}</th><th>{t("inventory.pick.result").text}</th><th>{t("inventory.pick.actions").text}</th></tr></thead>
              <tbody>{pick.data.lines.map((line, index) => (
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
                  <td>{pick.data.status === "OPEN" && line.stockLotId && (
                    <button type="button" onClick={() => setOverriding(line)}>{t("inventory.pick.override.open").text}</button>
                  )}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </section>
      )}
      {overriding && (
        <PickOverrideForm
          key={overriding.pickLineId}
          deliveryNoteId={deliveryNoteId}
          line={overriding}
          onClose={() => setOverriding(null)}
        />
      )}
    </main>
  );
}
