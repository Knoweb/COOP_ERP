import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale, skuText } from "../../shell/i18n/localName";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { SetLinesResponse, Sku } from "./pricingApi";
import { chipOf, errorText, reasonMessageId } from "./priceListState";

/** A line as the author edits it: numbers stay text until the server reads them. */
type Row = { skuId: string; uomCode: string; tierFromQty: string; price: string };

/**
 * One version of a trade price list (doc 30 section 5.3; 23A section 8, "Wholesale price list"):
 * its lines with volume tiers, and, for a draft, the editor: add an item from the catalogue, set
 * tiers and prices, save (the server answers each line; a refused line shows its reason inline),
 * then publish from a date, which asks for the second factor. A published version offers the
 * next draft. Prices are shown as the server sends them; the screen never computes one.
 */
export function TradePriceListPage() {
  const { listId = "" } = useParams();
  const t = useT();
  const api = usePricingApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canAuthor = useHasPermission("prc.pricelist.author");
  const canPublish = useHasPermission("prc.pricelist.publish");
  const saveKey = useIdempotencyKey();
  const publishKey = useIdempotencyKey();
  const versionKey = useIdempotencyKey();

  const detail = useQuery({
    queryKey: ["pricing", "priceList", listId],
    queryFn: () => api.getPriceList(listId)
  });

  const [rows, setRows] = useState<Row[]>([]);
  const [outcomes, setOutcomes] = useState<SetLinesResponse | null>(null);
  const [applyFrom, setApplyFrom] = useState("");

  useEffect(() => {
    if (detail.data) {
      setRows(
        detail.data.lines.map((line) => ({
          skuId: line.skuId,
          uomCode: line.uomCode,
          tierFromQty: String(line.tierFromQty),
          price: String(line.price)
        }))
      );
    }
  }, [detail.data]);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["pricing"] });
  };
  const forgetKeyOnProblem = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };

  const save = useMutation({
    mutationFn: () =>
      api.setLines(
        listId,
        rows.map((row) => ({
          skuId: row.skuId,
          uomCode: row.uomCode,
          tierFromQty: Number(row.tierFromQty),
          price: Number(row.price)
        })),
        saveKey.current()
      ),
    onSuccess: (result) => {
      saveKey.next();
      setOutcomes(result);
      if (result.saved) {
        refresh();
      }
    },
    onError: forgetKeyOnProblem(saveKey)
  });

  const publish = useMutation({
    mutationFn: () => api.publish(listId, applyFrom, publishKey.current()),
    onSuccess: () => {
      publishKey.next();
      refresh();
    },
    onError: forgetKeyOnProblem(publishKey)
  });

  const nextVersion = useMutation({
    mutationFn: () => api.draftNewVersion(listId, versionKey.current()),
    onSuccess: (draft) => {
      versionKey.next();
      refresh();
      navigate(`/pricing/lists/${draft.priceListId}`);
    },
    onError: forgetKeyOnProblem(versionKey)
  });

  if (detail.isLoading) {
    return <main className="shell-page">{t("pricing.list.loading").text}</main>;
  }
  if (detail.isError || !detail.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(detail.error, t("pricing.error.not_found").text)}</p>
        <Link to="/pricing">{t("pricing.back").text}</Link>
      </main>
    );
  }

  const list = detail.data.list;
  const editable = list.status === "DRAFT" && canAuthor;
  const setRow = (index: number, change: Partial<Row>) => {
    setOutcomes(null);
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...change } : row)));
  };
  const submitPublish = (event: FormEvent) => {
    event.preventDefault();
    publish.mutate();
  };

  return (
    <main className="shell-page">
      <Link to="/pricing">{t("pricing.back").text}</Link>
      <h1>{list.name}</h1>
      <p style={{ display: "flex", gap: "var(--space-2)", alignItems: "center" }}>
        <span>{t("pricing.version", undefined, { version: list.version }).text}</span>
        <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
        {list.applyFrom && <span>{t("pricing.applies_from", undefined, { date: list.applyFrom }).text}</span>}
      </p>

      <table>
        <thead>
          <tr>
            <th>{t("pricing.column.item").text}</th>
            <th>{t("pricing.column.unit").text}</th>
            <th>{t("pricing.column.tier").text}</th>
            <th>{t("pricing.column.price").text}</th>
            {editable && <th>{t("pricing.column.outcome").text}</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const outcome = outcomes?.outcomes[index];
            return (
              <tr key={index}>
                <td>
                  <SkuName skuId={row.skuId} />
                </td>
                <td>{row.uomCode}</td>
                <td>
                  {editable ? (
                    <input
                      type="number"
                      min="0"
                      step="any"
                      aria-label={t("pricing.column.tier").text}
                      value={row.tierFromQty}
                      onChange={(event) => setRow(index, { tierFromQty: event.target.value })}
                    />
                  ) : (
                    row.tierFromQty
                  )}
                </td>
                <td>
                  {editable ? (
                    <input
                      type="number"
                      min="0"
                      step="any"
                      aria-label={t("pricing.column.price").text}
                      value={row.price}
                      onChange={(event) => setRow(index, { price: event.target.value })}
                    />
                  ) : (
                    <MoneyDisplay amount={row.price} />
                  )}
                </td>
                {editable && (
                  <td>
                    {outcome && !outcome.ok && outcome.reason && (
                      <span role="alert" style={{ color: "var(--color-alert-text)" }}>
                        {t(reasonMessageId(outcome.reason)).text}
                      </span>
                    )}
                    {outcome?.review && <span role="note">{t(reasonMessageId(outcome.review)).text}</span>}
                    <button type="button" onClick={() => setRows((current) => current.filter((_, at) => at !== index))}>
                      {t("pricing.remove").text}
                    </button>
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length === 0 && <p>{t("pricing.lines.empty").text}</p>}

      {editable && (
        <section style={{ display: "grid", gap: "var(--space-2)", marginTop: "var(--space-3)" }}>
          <SkuPicker onPick={(sku) => setRows((current) => [...current, { skuId: sku.skuId, uomCode: sku.baseUomCode, tierFromQty: "0", price: "" }])} />
          <div>
            <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>
              {t("pricing.save").text}
            </button>
            {outcomes?.saved && <p role="status">{t("pricing.saved").text}</p>}
            {outcomes && !outcomes.saved && <p role="alert">{t("pricing.not_saved").text}</p>}
            {save.isError && <p role="alert">{errorText(save.error, t("pricing.error.generic").text)}</p>}
          </div>
          {canPublish && (
            <form onSubmit={submitPublish} style={{ display: "flex", gap: "var(--space-2)", alignItems: "end" }}>
              <label style={{ display: "grid", gap: "var(--space-half)" }}>
                {t("pricing.field.apply_from").text}
                <input type="date" required value={applyFrom} onChange={(event) => setApplyFrom(event.target.value)} />
              </label>
              <button type="submit" disabled={publish.isPending || !applyFrom || rows.length === 0}>
                {t("pricing.publish").text}
              </button>
              {publish.isError && <p role="alert">{errorText(publish.error, t("pricing.error.generic").text)}</p>}
            </form>
          )}
        </section>
      )}

      {list.status === "PUBLISHED" && canAuthor && (
        <p>
          <button type="button" disabled={nextVersion.isPending} onClick={() => nextVersion.mutate()}>
            {t("pricing.next_version").text}
          </button>
          {nextVersion.isError && <span role="alert">{errorText(nextVersion.error, t("pricing.error.generic").text)}</span>}
        </p>
      )}
    </main>
  );
}

/** The item's code and name, read from the catalogue (M2); the id while it loads. */
function SkuName({ skuId }: { skuId: string }) {
  const api = usePricingApi();
  const { locale } = useIntl();
  const sku = useQuery({ queryKey: ["pricing", "sku", skuId], queryFn: () => api.getSku(skuId), staleTime: Infinity });
  return <span>{sku.data ? skuText(sku.data, locale) : skuId}</span>;
}

/** Finds catalogue items by code or name and adds the one chosen as a new line at tier 0. */
function SkuPicker({ onPick }: { onPick: (sku: Sku) => void }) {
  const t = useT();
  const { locale } = useIntl();
  const api = usePricingApi();
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Sku[] | null>(null);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setFound(await api.searchSkus(q.trim()));
  };

  return (
    <form onSubmit={search} style={{ display: "grid", gap: "var(--space-1)" }}>
      <label style={{ display: "grid", gap: "var(--space-half)" }}>
        {t("pricing.field.find_item").text}
        <input type="search" value={q} onChange={(event) => setQ(event.target.value)} />
      </label>
      <div>
        <button type="submit" disabled={!q.trim()}>
          {t("pricing.find").text}
        </button>
      </div>
      {found?.length === 0 && <p>{t("pricing.find.none").text}</p>}
      {found && found.length > 0 && (
        <ul style={{ listStyle: "none", padding: 0 }}>
          {found.map((sku) => (
            <li key={sku.skuId}>
              <button type="button" onClick={() => onPick(sku)}>
                {t("pricing.add_item", undefined, { code: sku.skuCode, name: inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa) }).text}
              </button>
            </li>
          ))}
        </ul>
      )}
    </form>
  );
}
