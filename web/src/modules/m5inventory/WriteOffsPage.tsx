import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
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
import "./inventory.css";

type Category = RequestWriteOffRequest["category"];

const CATEGORIES: Category[] = [
  "EXPIRED",
  "DAMAGED_IN_STORE",
  "DAMAGED_IN_TRANSIT",
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
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <h1>{t("inventory.writeoff.title").text}</h1>
      <LocationPicker value={locationId} onChange={setLocationId} />

      <h2>{t("inventory.writeoff.list").text}</h2>
      {writeOffs.isError && <p role="alert">{errorText(writeOffs.error, t("inventory.error.generic").text)}</p>}
      {writeOffs.data?.length === 0 && <p>{t("inventory.writeoff.none").text}</p>}
      {writeOffs.data && writeOffs.data.length > 0 && (
        <table>
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
      )}

      {canRequest && locationId !== "" && (
        <section className="inventory-section">
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
            <table>
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
                      <td>{lot.expiryDate ?? ""}</td>
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
