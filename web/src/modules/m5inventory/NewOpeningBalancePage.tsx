import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale, skuText } from "../../shell/i18n/localName";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useInventoryApi, type Sku } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { errorText, lineOf, rowReady, type CountedRow } from "./stockView";

/**
 * Prepare the opening stock of a location (doc 25 section 4.7; 25A M5-10, M5-12 demo scope): the
 * counted lines, each an item from the catalogue with its batch number, expiry, printed MRP,
 * quantity, cost and condition. The server registers each batch in M2 and keeps the balance as a
 * DRAFT; its card follows, where it is signed and countersigned.
 */
export function NewOpeningBalancePage() {
  const t = useT();
  const { locale } = useIntl();
  const api = useInventoryApi();
  const navigate = useNavigate();
  const key = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [rows, setRows] = useState<CountedRow[]>([]);

  const prepare = useMutation({
    mutationFn: () => api.prepare(locationId, rows.map(lineOf), key.current()),
    onSuccess: (balance) => {
      key.next();
      navigate(`/inventory/opening/${balance.openingBalanceId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  const setRow = (index: number, change: Partial<CountedRow>) =>
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...change } : row)));
  const add = (sku: Sku) =>
    setRows((current) => [
      ...current,
      { skuId: sku.skuId, label: skuText(sku, locale), batchNo: "", expiryDate: "", printedMrp: "", qty: "", unitCost: "", condition: "GOOD" }
    ]);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    prepare.mutate();
  };

  const cell = (index: number, field: "batchNo" | "expiryDate" | "printedMrp" | "qty" | "unitCost", type: string, labelId: string) => (
    <td>
      <input
        type={type}
        min={type === "number" ? "0" : undefined}
        step={type === "number" ? "any" : undefined}
        aria-label={t(labelId).text}
        value={rows[index][field]}
        onChange={(event) => setRow(index, { [field]: event.target.value })}
      />
    </td>
  );

  return (
    <main className="shell-page">
      <Link to="/inventory">{t("inventory.back").text}</Link>
      <h1>{t("inventory.opening.new.title").text}</h1>
      <form onSubmit={submit} style={{ display: "grid", gap: "var(--space-2)" }}>
        <LocationPicker value={locationId} onChange={setLocationId} />
        <table>
          <thead>
            <tr>
              <th>{t("inventory.column.item").text}</th>
              <th>{t("inventory.column.batch").text}</th>
              <th>{t("inventory.column.expiry").text}</th>
              <th>{t("inventory.column.mrp").text}</th>
              <th>{t("inventory.column.qty").text}</th>
              <th>{t("inventory.column.unit_cost").text}</th>
              <th>{t("inventory.column.condition").text}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={index}>
                <td>{row.label}</td>
                {cell(index, "batchNo", "text", "inventory.column.batch")}
                {cell(index, "expiryDate", "date", "inventory.column.expiry")}
                {cell(index, "printedMrp", "number", "inventory.column.mrp")}
                {cell(index, "qty", "number", "inventory.column.qty")}
                {cell(index, "unitCost", "number", "inventory.column.unit_cost")}
                <td>
                  <select
                    aria-label={t("inventory.column.condition").text}
                    value={row.condition}
                    onChange={(event) => setRow(index, { condition: event.target.value as CountedRow["condition"] })}
                  >
                    <option value="GOOD">{t("inventory.condition.GOOD").text}</option>
                    <option value="DAMAGED">{t("inventory.condition.DAMAGED").text}</option>
                  </select>
                </td>
                <td>
                  <button type="button" onClick={() => setRows((current) => current.filter((_, at) => at !== index))}>
                    {t("inventory.remove").text}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p>{t("inventory.opening.lines.empty").text}</p>}
        <div>
          <button type="submit" disabled={prepare.isPending || !locationId || rows.length === 0 || !rows.every(rowReady)}>
            {t("inventory.opening.prepare").text}
          </button>
        </div>
        {prepare.isError && <p role="alert">{errorText(prepare.error, t("inventory.error.generic").text)}</p>}
      </form>
      <SkuFinder onPick={add} />
    </main>
  );
}

/** Finds catalogue items in use (LOCAL or SHARED) by code or name, and adds the one chosen as a line. */
function SkuFinder({ onPick }: { onPick: (sku: Sku) => void }) {
  const t = useT();
  const { locale } = useIntl();
  const api = useInventoryApi();
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Sku[] | null>(null);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setFound(await api.searchSkus(q.trim()));
  };

  return (
    <form onSubmit={search} style={{ display: "grid", gap: "var(--space-1)", marginTop: "var(--space-3)" }}>
      <label style={{ display: "grid", gap: "var(--space-half)" }}>
        {t("inventory.field.find_item").text}
        <input type="search" value={q} onChange={(event) => setQ(event.target.value)} />
      </label>
      <div>
        <button type="submit" disabled={!q.trim()}>
          {t("inventory.find").text}
        </button>
      </div>
      {found?.length === 0 && <p>{t("inventory.find.none").text}</p>}
      {found && found.length > 0 && (
        <ul style={{ listStyle: "none", padding: 0 }}>
          {found.map((sku) => (
            <li key={sku.skuId}>
              <button type="button" onClick={() => onPick(sku)}>
                {t("inventory.add_item", undefined, { code: sku.skuCode, name: inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa) }).text}
              </button>
            </li>
          ))}
        </ul>
      )}
    </form>
  );
}
