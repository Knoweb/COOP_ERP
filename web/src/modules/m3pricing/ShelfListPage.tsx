import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { inLocale, skuText } from "../../shell/i18n/localName";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { RetailPrice, SetLinesResponse } from "./pricingApi";
import { SkuName, SkuPicker } from "./SkuPicker";
import { bindingCeiling, chipOf, errorText, incompleteLines, reasonMessageId } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

/** A line as the author edits it: the price stays text until the server reads it. */
type Row = { skuId: string; uomCode: string; price: string };

/**
 * One version of a shelf price list (23A section 8, "Shelf price list"; doc 23 flow 6.1): a RETAIL
 * list of the society, or an ADVISORY list of the Federation. Prices are per unit and include VAT;
 * there are no quantity tiers. The advisory price is shown beside each line, read-only. Saving
 * checks every line against the ceilings (the control price in force, and for RETAIL the lowest
 * printed MRP in stock at the society's locations); the server names the binding ceiling of each
 * line, and a line above it is refused with it, so the list cannot be published until it is fixed.
 * A published RETAIL version offers the price check: the price the engine resolves at a shop.
 */
export function ShelfListPage() {
  const { listId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canAuthor = useHasPermission("prc.pricelist.author");
  const canPublish = useHasPermission("prc.pricelist.publish");
  const saveKey = useIdempotencyKey();
  const publishKey = useIdempotencyKey();
  const versionKey = useIdempotencyKey();

  const detail = useQuery({ queryKey: ["pricing", "priceList", listId], queryFn: () => api.getPriceList(listId) });
  const advisoryDate = detail.data?.list.applyFrom ?? businessToday();
  const advisory = useQuery({
    queryKey: ["pricing", "advisory", advisoryDate],
    queryFn: () => api.advisoryLines(advisoryDate),
    enabled: detail.data?.list.kind === "RETAIL"
  });
  const advisoryBySku = useMemo(() => {
    const prices = new Map<string, number>();
    for (const line of advisory.data ?? []) {
      prices.set(`${line.skuId}/${line.uomCode}`, line.price);
    }
    return prices;
  }, [advisory.data]);

  const [rows, setRows] = useState<Row[]>([]);
  const incomplete = incompleteLines(rows);
  const [outcomes, setOutcomes] = useState<SetLinesResponse | null>(null);
  const [applyFrom, setApplyFrom] = useState("");

  useEffect(() => {
    if (detail.data) {
      setRows(detail.data.lines.map((line) => ({ skuId: line.skuId, uomCode: line.uomCode, price: String(line.price) })));
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
        rows.map((row) => ({ skuId: row.skuId, uomCode: row.uomCode, tierFromQty: 0, price: Number(row.price) })),
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
      navigate(`/pricing/shelf/${draft.priceListId}`);
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
        <Link className="back-link" to="/pricing/shelf">
          {t("pricing.shelf.back").text}
        </Link>
      </main>
    );
  }

  const list = detail.data.list;
  const retail = list.kind === "RETAIL";
  const editable = list.status === "DRAFT" && canAuthor;
  const refusedLines = outcomes?.outcomes.filter((outcome) => !outcome.ok).length ?? 0;
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
      <Link className="back-link" to="/pricing/shelf">
        {t("pricing.shelf.back").text}
      </Link>
      <h1>{list.name}</h1>
      <PricingTabs />
      <p className="pricing-header-row">
        <span>{t(`pricing.kind.${list.kind.toLowerCase()}`).text}</span>
        <span>{t("pricing.version", undefined, { version: list.version }).text}</span>
        <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
        {list.applyFrom && <span>{t("pricing.applies_from", undefined, { date: formatDate(list.applyFrom) }).text}</span>}
      </p>

      <div className="modern-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table">
            <thead>
              <tr>
                <th>{t("pricing.column.item").text}</th>
                <th>{t("pricing.column.unit").text}</th>
                <th>{t("pricing.column.shelf_price").text}</th>
                {retail && <th>{t("pricing.column.advisory").text}</th>}
                <th>{t("pricing.column.ceiling").text}</th>
                {editable && <th>{t("pricing.column.outcome").text}</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => {
                const outcome = outcomes?.outcomes[index];
                const ceiling = bindingCeiling(outcome);
                const advice = advisoryBySku.get(`${row.skuId}/${row.uomCode}`);
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
                          step="0.01"
                          aria-label={t("pricing.column.shelf_price").text}
                          value={row.price}
                          onChange={(event) => setRow(index, { price: event.target.value })}
                        />
                      ) : (
                        <MoneyDisplay amount={row.price} />
                      )}
                    </td>
                    {retail && <td>{advice == null ? "" : <MoneyDisplay amount={advice} />}</td>}
                    <td>
                      {ceiling && (
                        <span>
                          {t(`pricing.ceiling.${ceiling.kind.toLowerCase()}`, undefined, { ref: ceiling.ref }).text}{" "}
                          <MoneyDisplay amount={ceiling.value} />
                        </span>
                      )}
                    </td>
                    {editable && (
                      <td>
                        {outcome && !outcome.ok && outcome.reason && (
                          <span role="alert" className="pricing-alert-text">
                            {t(reasonMessageId(outcome.reason)).text}
                          </span>
                        )}
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
        </div>
      </div>
      {rows.length === 0 && <p>{t("pricing.lines.empty").text}</p>}

      {editable && (
        <section className="pricing-section">
          <SkuPicker
            onPick={(sku) =>
              setRows((current) =>
                current.some((row) => row.skuId === sku.skuId)
                  ? current
                  : [...current, { skuId: sku.skuId, uomCode: sku.baseUomCode, price: "" }]
              )
            }
          />
          <div>
            <button type="button" disabled={save.isPending || incomplete.length > 0} onClick={() => save.mutate()}>
              {t("pricing.save").text}
            </button>
            {incomplete.length > 0 && <p role="alert">{t("pricing.shelf.incomplete", undefined, { rows: incomplete.join(", ") }).text}</p>}
            {outcomes?.saved && <p role="status">{t("pricing.saved").text}</p>}
            {outcomes && !outcomes.saved && (
              <p role="alert">{t("pricing.shelf.not_saved", undefined, { count: refusedLines }).text}</p>
            )}
            {save.isError && <p role="alert">{errorText(save.error, t("pricing.error.generic").text)}</p>}
          </div>
          {canPublish && (
            <form onSubmit={submitPublish} className="pricing-publish-form">
              <label className="pricing-form-field">
                {t("pricing.field.apply_from").text}
                <input type="date" required value={applyFrom} onChange={(event) => setApplyFrom(event.target.value)} />
              </label>
              <button
                type="submit"
                disabled={publish.isPending || !applyFrom || rows.length === 0 || refusedLines > 0}
              >
                {t("pricing.publish").text}
              </button>
              {refusedLines > 0 && <span className="pricing-muted">{t("pricing.shelf.fix_to_publish").text}</span>}
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

      {list.status !== "DRAFT" && retail && rows.length > 0 && <PriceCheck rows={rows} />}
    </main>
  );
}

/**
 * The price check (doc 23 section 5.2, ResolveRetailPrice): what the engine charges for one unit of
 * an item of the list at one of the society's shops on a date, bounded by the MRP of the batches on
 * that shop's shelves and by the control price. Shown as the server answers it.
 */
function PriceCheck({ rows }: { rows: Row[] }) {
  const t = useT();
  const { locale } = useIntl();
  const api = usePricingApi();
  const locations = useQuery({ queryKey: ["pricing", "locations"], queryFn: () => api.locations() });
  const shops = (locations.data ?? []).filter((location) => location.locationType === "SHOP");
  // An option holds text only, so the item names are read here rather than by <SkuName>; the same
  // query keys, so the table's lookups are reused.
  const skus = useQueries({
    queries: rows.map((row) => ({
      queryKey: ["pricing", "sku", row.skuId],
      queryFn: () => api.getSku(row.skuId),
      staleTime: Infinity
    }))
  });
  const [locationId, setLocationId] = useState("");
  const [skuId, setSkuId] = useState("");
  const [date, setDate] = useState(businessToday());
  const [result, setResult] = useState<RetailPrice | null>(null);

  const check = useMutation({
    mutationFn: () => {
      const row = rows.find((candidate) => candidate.skuId === skuId);
      return api.resolveRetailPrice(locationId, skuId, row?.uomCode ?? "", date);
    },
    onSuccess: (price) => setResult(price)
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    check.mutate();
  };

  return (
    <section className="pricing-section" aria-labelledby="pricing-check-title">
      <h2 id="pricing-check-title">{t("pricing.check.title").text}</h2>
      <form onSubmit={submit} className="pricing-form-grid">
        <label className="pricing-form-field">
          {t("pricing.check.shop").text}
          <select aria-label={t("pricing.check.shop").text} required value={locationId} onChange={(event) => setLocationId(event.target.value)}>
            <option value="">{t("pricing.check.choose").text}</option>
            {shops.map((shop) => (
              <option key={shop.locationId} value={shop.locationId}>
                {inLocale(locale, shop.nameEn, shop.nameSi, shop.nameTa)}
              </option>
            ))}
          </select>
        </label>
        <label className="pricing-form-field">
          {t("pricing.column.item").text}
          <select aria-label={t("pricing.column.item").text} required value={skuId} onChange={(event) => setSkuId(event.target.value)}>
            <option value="">{t("pricing.check.choose").text}</option>
            {rows.map((row, index) => {
              const sku = skus[index]?.data;
              return (
                <option key={row.skuId} value={row.skuId}>
                  {sku ? skuText(sku, locale) : row.skuId}
                </option>
              );
            })}
          </select>
        </label>
        <label className="pricing-form-field">
          {t("pricing.check.date").text}
          <input type="date" required value={date} onChange={(event) => setDate(event.target.value)} />
        </label>
        <button type="submit" disabled={check.isPending || !locationId || !skuId || !date}>
          {t("pricing.check.run").text}
        </button>
      </form>
      {check.isError && <p role="alert">{errorText(check.error, t("pricing.error.generic").text)}</p>}
      {result && (
        <dl className="pricing-section" aria-live="polite">
          {result.sellable ? (
            <>
              <dt>{t("pricing.check.price").text}</dt>
              <dd>
                <MoneyDisplay amount={result.unitPrice ?? ""} />
              </dd>
              <dt>{t("pricing.check.bound").text}</dt>
              <dd>{t(`pricing.cap.${result.capReason.toLowerCase()}`).text}</dd>
            </>
          ) : (
            <>
              <dt>{t("pricing.check.price").text}</dt>
              <dd>{t(`pricing.check.not_sellable.${result.reason === "price.needs_pick" ? "pick" : "no_line"}`).text}</dd>
            </>
          )}
          <dt>{t("pricing.check.list_price").text}</dt>
          <dd>{result.listPrice == null ? "" : <MoneyDisplay amount={result.listPrice} />}</dd>
          <dt>{t("pricing.check.mrp").text}</dt>
          <dd>{result.mrpApplied == null ? "" : <MoneyDisplay amount={result.mrpApplied} />}</dd>
          <dt>{t("pricing.check.control").text}</dt>
          <dd>{result.controlPrice == null ? "" : <MoneyDisplay amount={result.controlPrice} />}</dd>
          <dt>{t("pricing.check.policy").text}</dt>
          <dd>{t(`pricing.policy.${result.policy.toLowerCase()}`).text}</dd>
        </dl>
      )}
    </section>
  );
}
