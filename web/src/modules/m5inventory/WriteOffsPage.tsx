import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant, useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi, type RequestWriteOffRequest } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { SkuLabel } from "./SkuLabel";
import { lotKey, writeOffChip, writeOffLinesOf } from "./stockControl";
import { errorText, isSyntheticBatchNo } from "./stockView";
import { PageHeader } from "../../shell/components/PageHeader";
import "./inventory.css";

type Category = RequestWriteOffRequest["category"];

const CATEGORIES: Category[] = [
  "EXPIRED",
  "DAMAGED_IN_STORE",
  "DAMAGED_IN_TRANSIT",
  "WASTE",
  "SPOILAGE",
  "THEFT",
  "SHRINKAGE_UNEXPLAINED",
  "STAFF_CONSUMPTION",
  "SAMPLES",
  "DONATION",
  "OTHER"
];

/**
 * The damage and expiry register of a location (25A section 8, "Damage/expiry register", web): the
 * write-offs there with their state, and a new draft: the category, a quantity against the
 * location's lots in pick order, a note. Photographs, the submit and the approvals are on the
 * write-off's own page, which the draft opens.
 */
export function WriteOffsPage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useInventoryApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const canRequest = useHasPermission("inv.writeoff.request");
  const requestKey = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [category, setCategory] = useState<Category | "">("");
  const [note, setNote] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});

  const writeOffs = useQuery({
    queryKey: ["inventory", "write-offs", locationId],
    queryFn: () => api.writeOffs(locationId),
    enabled: locationId !== ""
  });
  const lots = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== "" && canRequest
  });
  const available = (lots.data ?? []).filter((lot) => lot.qtyOnHand > 0);
  const lines = writeOffLinesOf(available, quantities);

  const request = useMutation({
    mutationFn: () =>
      api.requestWriteOff(
        { locationId, category: category as Category, note: note.trim() || undefined, lines },
        requestKey.current()
      ),
    onSuccess: (writeOff) => {
      requestKey.next();
      queryClient.invalidateQueries({ queryKey: ["inventory"] });
      navigate(`/inventory/write-offs/${writeOff.writeOffId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        requestKey.next();
      }
    }
  });

  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/inventory">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("inventory.back").text}
        </Link>
      </div>
      <PageHeader
        icon="stock"
        title={t("inventory.title").text}
      />

      <div className="inventory-control-links">
        <Link className="action-link" to="/inventory/counts">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M9 11l3 3L22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </svg>
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link action-link--primary" to="/inventory/write-offs">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="9" y1="15" x2="15" y2="15" />
          </svg>
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/repacks">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="m21 16-4 4-4-4" />
            <path d="M17 20V4" />
            <path d="m3 8 4-4 4 4" />
            <path d="M7 4v16" />
          </svg>
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/transfer-requests">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <rect x="1" y="3" width="15" height="13" />
            <polygon points="16 8 20 8 23 11 23 16 16 16 8" />
            <circle cx="5.5" cy="18.5" r="2.5" />
            <circle cx="18.5" cy="18.5" r="2.5" />
          </svg>
          <span>{t("inventory.request.link").text}</span>
        </Link>
      </div>

      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      <section className="modern-table-card">
        <div style={{ padding: 'var(--space-4) var(--space-4) 0' }}>
          <h2>{t("inventory.writeoff.list").text}</h2>
        </div>
        {writeOffs.isError && <p role="alert" style={{ padding: '0 var(--space-4)' }}>{errorText(writeOffs.error, t("inventory.error.generic").text)}</p>}
        {writeOffs.data?.length === 0 && <p style={{ padding: '0 var(--space-4) var(--space-4)' }}>{t("inventory.writeoff.none").text}</p>}
        {writeOffs.data && writeOffs.data.length > 0 && (
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.writeoff.document").text}</th>
                  <th>{t("inventory.writeoff.category").text}</th>
                  <th>{t("inventory.writeoff.value").text}</th>
                  <th>{t("inventory.repack.column.status").text}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {writeOffs.data.map((writeOff) => (
                  <tr key={writeOff.writeOffId}>
                    <td>{writeOff.documentNo ?? (writeOff.requestedAt ? formatInstant(writeOff.requestedAt) : "")}</td>
                    <td>{t(`inventory.writeoff.category.${writeOff.category}`).text}</td>
                    <td className="numeric-cell">{writeOff.value == null ? "" : <MoneyDisplay amount={writeOff.value} />}</td>
                    <td>
                      <StateChip
                        state={writeOffChip(writeOff.status)}
                        label={t(`inventory.writeoff.status.${writeOff.status}`).text}
                      />
                    </td>
                    <td>
                      <Link to={`/inventory/write-offs/${writeOff.writeOffId}`}>{t("inventory.writeoff.open").text}</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {canRequest && locationId !== "" && (
        <section className="modern-table-card inventory-section" style={{ marginTop: 'var(--space-4)', padding: 'var(--space-4)' }}>
          <h2>{t("inventory.writeoff.new").text}</h2>
          <label className="inventory-form-field">
            {t("inventory.writeoff.category").text}
            <select
              aria-label={t("inventory.writeoff.category").text}
              value={category}
              onChange={(event) => setCategory(event.target.value as Category)}
            >
              <option value="" />
              {CATEGORIES.map((code) => (
                <option key={code} value={code}>
                  {t(`inventory.writeoff.category.${code}`).text}
                </option>
              ))}
            </select>
          </label>
          {available.length === 0 && <p>{t("inventory.balances.empty").text}</p>}
          {available.length > 0 && (
            <div className="modern-table-scroll" style={{ marginTop: 'var(--space-3)' }}>
              <table className="modern-table stock-table">
                <thead>
                  <tr>
                    <th>{t("inventory.column.item").text}</th>
                    <th>{t("inventory.column.batch").text}</th>
                    <th>{t("inventory.column.expiry").text}</th>
                    <th>{t("inventory.column.condition").text}</th>
                    <th>{t("inventory.column.on_hand").text}</th>
                    <th>{t("inventory.writeoff.qty").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {available.map((lot) => {
                    const key = lotKey(lot.batchId, lot.condition);
                    const batch = isSyntheticBatchNo(lot.batchNo)
                      ? t("inventory.field.batch_not_tracked").text
                      : (lot.batchNo ?? "");
                    return (
                      <tr key={lot.stockLotId}>
                        <td>
                          <SkuLabel skuId={lot.skuId} />
                        </td>
                        <td>{batch}</td>
                        <td>{lot.expiryDate ? formatDate(lot.expiryDate) : ""}</td>
                        <td>{t(`inventory.condition.${lot.condition}`).text}</td>
                        <td className="numeric-cell">{lot.qtyOnHand}</td>
                        <td>
                          <input
                            inputMode="decimal"
                            aria-label={
                              t("inventory.writeoff.qty_for", undefined, {
                                batch: `${batch} ${t(`inventory.condition.${lot.condition}`).text}`
                              }).text
                            }
                            value={quantities[key] ?? ""}
                            onChange={(event) => setQuantities({ ...quantities, [key]: event.target.value })}
                          />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          <label className="inventory-form-field">
            {t("inventory.writeoff.note").text}
            <textarea value={note} rows={2} onChange={(event) => setNote(event.target.value)} />
          </label>
          <div className="inventory-action-bar">
            <button
              type="button"
              disabled={category === "" || lines.length === 0 || request.isPending}
              onClick={() => request.mutate()}
            >
              {t("inventory.writeoff.save").text}
            </button>
          </div>
          {request.isError && <p role="alert">{errorText(request.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}
    </main>
  );
}
