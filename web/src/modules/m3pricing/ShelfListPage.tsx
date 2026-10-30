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
import { ShelfLabelPrintAction } from "./ShelfLabelPrintAction";
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
  const sourceVersionId =
    detail.data?.list.status === "DRAFT"
      ? detail.data.list.sourceVersionId
      : null;
  const sourceVersion = useQuery({
    queryKey: ["pricing", "priceList", sourceVersionId],
    queryFn: () => api.getPriceList(sourceVersionId!),
    enabled: Boolean(sourceVersionId)
  });

  const advisoryDate = detail.data?.list.applyFrom ?? businessToday();
  const advisory = useQuery({
    queryKey: ["pricing", "advisory", advisoryDate],
    queryFn: () => api.advisoryLines(advisoryDate),
    enabled: detail.data?.list.kind === "RETAIL"
  });
  const retailVersions = useQuery({
    queryKey: ["pricing", "priceLists", "RETAIL"],
    queryFn: () => api.listPriceLists("RETAIL"),
    enabled: detail.data?.list.kind === "RETAIL" && detail.data.list.status !== "DRAFT"
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
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M15 18l-6-6 6-6" />
            <path d="M9 12h10" />
          </svg>
          <span>{t("pricing.shelf.back").text}</span>
        </Link>
      </main>
    );
  }

  const list = detail.data.list;
  const retail = list.kind === "RETAIL";
  const effectiveRetail = retailVersions.data
    ?.filter(
      (candidate) =>
        candidate.rootPriceListId === list.rootPriceListId &&
        (candidate.status === "PUBLISHED" || candidate.status === "SUPERSEDED") &&
        candidate.applyFrom != null &&
        candidate.applyFrom <= businessToday()
    )
    .sort((left, right) => right.version - left.version)[0];
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
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        <span>{t("pricing.shelf.back").text}</span>
      </Link>

      <div className="pricing-header-card">
        <h1>{list.name}</h1>
        <div className="pricing-header-row">
          <span className="pricing-meta-badge">{t(`pricing.kind.${list.kind.toLowerCase()}`).text}</span>
          <span className="pricing-meta-badge">{t("pricing.version", undefined, { version: list.version }).text}</span>
          <StateChip state={chipOf(list.status)} label={t(`pricing.status.${list.status.toLowerCase()}`).text} />
          {list.applyFrom && <span className="pricing-meta-badge">{t("pricing.applies_from", undefined, { date: formatDate(list.applyFrom) }).text}</span>}
        </div>
      </div>

      {list.status === "SUPERSEDED" && canAuthor && (
        <p role="alert">{t("pricing.warning.superseded_source").text}</p>
      )}
      {list.status === "DRAFT" && sourceVersion.data?.list.status === "SUPERSEDED" && (
        <p role="alert">{t("pricing.warning.draft_from_superseded").text}</p>
      )}

      <div className="modern-table-card">
        <div className="modern-table-scroll">
          <table className="modern-table">
            <thead>
              <tr>
                <th>{t("pricing.column.item").text}</th>
                <th>{t("pricing.column.unit").text}</th>
                <th className="pricing-cell-numeric">{t("pricing.column.shelf_price").text}</th>
                {retail && <th className="pricing-cell-numeric">{t("pricing.column.advisory").text}</th>}
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
                    <td className="pricing-cell-item">
                      <SkuName skuId={row.skuId} />
                    </td>
                    <td>{row.uomCode}</td>
                    <td className="pricing-cell-numeric">
                      {editable ? (
                        <input
                          type="number"
                          min="0"
                          step="0.01"
                          className="pricing-table-input"
                          aria-label={t("pricing.column.shelf_price").text}
                          value={row.price}
                          onChange={(event) => setRow(index, { price: event.target.value })}
                        />
                      ) : (
                        <MoneyDisplay amount={row.price} />
                      )}
                    </td>
                    {retail && (
                      <td className="pricing-cell-numeric">
                        {advice == null ? "" : <MoneyDisplay amount={advice} />}
                      </td>
                    )}
                    <td>
                      {ceiling && (
                        <span className="pricing-ceiling-badge">
                          {t(`pricing.ceiling.${ceiling.kind.toLowerCase()}`, undefined, { ref: ceiling.ref }).text}{" "}
                          <MoneyDisplay amount={ceiling.value} />
                        </span>
                      )}
                    </td>
                    {editable && (
                      <td>
                        <div className="pricing-outcome-cell">
                          {outcome && !outcome.ok && outcome.reason && (
                            <span role="alert" className="pricing-alert-pill">
                              {t(reasonMessageId(outcome.reason)).text}
                            </span>
                          )}
                          <button
                            type="button"
                            className="pricing-btn-remove"
                            onClick={() => setRows((current) => current.filter((_, at) => at !== index))}
                          >
                            {t("pricing.remove").text}
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
      {rows.length === 0 && (
        <div className="pricing-card pricing-empty-card">
          <p className="pricing-muted">{t("pricing.lines.empty").text}</p>
        </div>
      )}

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
          <div className="pricing-card pricing-actions-card">
            <div className="pricing-actions-row">
              <button
                type="button"
                className="modern-btn modern-btn--primary"
                disabled={save.isPending || incomplete.length > 0}
                onClick={() => save.mutate()}
              >
                {t("pricing.save").text}
              </button>
              {incomplete.length > 0 && (
                <span role="alert" className="pricing-alert-box pricing-alert-box--error">
                  {t("pricing.shelf.incomplete", undefined, { rows: incomplete.join(", ") }).text}
                </span>
              )}
              {outcomes?.saved && <span role="status" className="pricing-alert-box pricing-alert-box--success">{t("pricing.saved").text}</span>}
              {outcomes && !outcomes.saved && (
                <span role="alert" className="pricing-alert-box pricing-alert-box--error">
                  {t("pricing.shelf.not_saved", undefined, { count: refusedLines }).text}
                </span>
              )}
              {save.isError && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(save.error, t("pricing.error.generic").text)}</span>}
            </div>

            {canPublish && (
              <form onSubmit={submitPublish} className="pricing-publish-form">
                <label className="modern-field">
                  <span className="modern-field__label pricing-filter-label">{t("pricing.field.apply_from").text}</span>
                  <input
                    type="date"
                    required
                    className="pricing-input"
                    value={applyFrom}
                    onChange={(event) => setApplyFrom(event.target.value)}
                  />
                </label>
                <button
                  type="submit"
                  className="modern-btn modern-btn--primary"
                  disabled={publish.isPending || !applyFrom || rows.length === 0 || refusedLines > 0}
                >
                  {t("pricing.publish").text}
                </button>
                {refusedLines > 0 && <span className="pricing-muted">{t("pricing.shelf.fix_to_publish").text}</span>}
                {publish.isError && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(publish.error, t("pricing.error.generic").text)}</span>}
              </form>
            )}
          </div>
        </section>
      )}

      {(list.status === "PUBLISHED" || list.status === "SUPERSEDED") && canAuthor && (
        <div className="pricing-card pricing-actions-card">
          <div className="pricing-actions-row">
            <button
              type="button"
              className="modern-btn modern-btn--primary"
              disabled={nextVersion.isPending}
              onClick={() => nextVersion.mutate()}
            >
              {t("pricing.next_version").text}
            </button>
            {nextVersion.isError && <span role="alert" className="pricing-alert-box pricing-alert-box--error">{errorText(nextVersion.error, t("pricing.error.generic").text)}</span>}
          </div>
        </div>
      )}

      {list.status !== "DRAFT" && retail && rows.length > 0 && <PriceCheck rows={rows} />}
      {effectiveRetail?.priceListId === list.priceListId && rows.length > 0 && (
        <ShelfLabelPrintAction skuIds={rows.map((row) => row.skuId)} />
      )}
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
    <section className="pricing-card pricing-check-card" aria-labelledby="pricing-check-title">
      <h2 id="pricing-check-title">{t("pricing.check.title").text}</h2>
      <form onSubmit={submit} className="pricing-form-grid">
        <label className="modern-field">
          <span className="modern-field__label pricing-filter-label">{t("pricing.check.shop").text}</span>
          <div className="modern-select">
            <select
              className="pricing-select"
              aria-label={t("pricing.check.shop").text}
              required
              value={locationId}
              onChange={(event) => setLocationId(event.target.value)}
            >
              <option value="">{t("pricing.check.choose").text}</option>
              {shops.map((shop) => (
                <option key={shop.locationId} value={shop.locationId}>
                  {inLocale(locale, shop.nameEn, shop.nameSi, shop.nameTa)}
                </option>
              ))}
            </select>
            <svg className="modern-select__arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </div>
        </label>
        <label className="modern-field">
          <span className="modern-field__label pricing-filter-label">{t("pricing.column.item").text}</span>
          <div className="modern-select">
            <select
              className="pricing-select"
              aria-label={t("pricing.column.item").text}
              required
              value={skuId}
              onChange={(event) => setSkuId(event.target.value)}
            >
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
            <svg className="modern-select__arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </div>
        </label>
        <label className="modern-field">
          <span className="modern-field__label pricing-filter-label">{t("pricing.check.date").text}</span>
          <input
            type="date"
            className="pricing-input"
            required
            value={date}
            onChange={(event) => setDate(event.target.value)}
          />
        </label>
        <button
          type="submit"
          className="modern-btn modern-btn--primary"
          disabled={check.isPending || !locationId || !skuId || !date}
        >
          {t("pricing.check.run").text}
        </button>
      </form>
      {check.isError && (
        <p role="alert" className="pricing-alert-box pricing-alert-box--error pricing-alert-box--spaced">
          {errorText(check.error, t("pricing.error.generic").text)}
        </p>
      )}
      {result && (
        <div className="pricing-check-result" aria-live="polite">
          <dl className="pricing-check-highlight">
            <div>
              <dt className="pricing-check-highlight__label">{t("pricing.check.price").text}</dt>
              <dd className="pricing-check-highlight__value">
                {result.sellable ? (
                  <MoneyDisplay amount={result.unitPrice ?? ""} />
                ) : (
                  <span>{t(`pricing.check.not_sellable.${result.reason === "price.needs_pick" ? "pick" : "no_line"}`).text}</span>
                )}
              </dd>
            </div>
            {result.sellable && (
              <span className="pricing-meta-badge">
                {t("pricing.check.bound").text}: {t(`pricing.cap.${result.capReason.toLowerCase()}`).text}
              </span>
            )}
          </dl>

          <dl className="pricing-check-grid">
            <div className="pricing-check-item">
              <dt className="pricing-check-item__label">{t("pricing.check.list_price").text}</dt>
              <dd className="pricing-check-item__value">
                {result.listPrice == null ? "—" : <MoneyDisplay amount={result.listPrice} />}
              </dd>
            </div>
            <div className="pricing-check-item">
              <dt className="pricing-check-item__label">{t("pricing.check.mrp").text}</dt>
              <dd className="pricing-check-item__value">
                {result.mrpApplied == null ? "—" : <MoneyDisplay amount={result.mrpApplied} />}
              </dd>
            </div>
            <div className="pricing-check-item">
              <dt className="pricing-check-item__label">{t("pricing.check.control").text}</dt>
              <dd className="pricing-check-item__value">
                {result.controlPrice == null ? "—" : <MoneyDisplay amount={result.controlPrice} />}
              </dd>
            </div>
            <div className="pricing-check-item">
              <dt className="pricing-check-item__label">{t("pricing.check.policy").text}</dt>
              <dd className="pricing-check-item__value">
                {t(`pricing.policy.${result.policy.toLowerCase()}`).text}
              </dd>
            </div>
          </dl>
        </div>
      )}
    </section>
  );
}
