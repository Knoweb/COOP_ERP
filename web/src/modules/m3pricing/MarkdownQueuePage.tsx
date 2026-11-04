import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale } from "../../shell/i18n/localName";
import { businessToday, formatMoneyDigits, useFormatDate } from "../../shell/i18n/formats";
import { useScope } from "../../shell/scope/useScope";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { Rule } from "./pricingApi";
import { SkuName } from "./SkuPicker";
import { errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import { PricingSelect } from "./PricingSelect";
import "./pricing.css";

function daysBetween(fromIsoDate: string, toIsoDate: string): number {
  const fromMs = Date.parse(fromIsoDate);
  const toMs = Date.parse(toIsoDate);
  return Math.round((toMs - fromMs) / (1000 * 60 * 60 * 24));
}

type ReasonFilter = "ALL" | "NEAR_EXPIRY" | "SLOW_MOVING";
type StatusFilter = "ALL" | "ACTIVE";

interface MarkdownQueueItem {
  id: string;
  skuId: string;
  skuCode?: string;
  stockLotId?: string | null;
  batchNo?: string | null;
  expiryDate?: string | null;
  daysRemaining?: number | null;
  qtyOnHand: number;
  condition?: string;
  reason: string;
  tierOrClassification?: string;
  basePrice?: number;
  markdownPercent?: number;
  discountedPrice?: number;
  matchedRule?: Rule;
  status: string;
}


/**
 * The markdown queue (/pricing/markdown; 23A section 8; doc 23 section 4.3; M3-10; PGL-M1-07).
 * Joins M5 inventory lots and slow-moving items with active M3 markdown rules and calculated discounts.
 */
export function MarkdownQueuePage() {
  const t = useT();
  const { locale } = useIntl();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();
  const today = businessToday();

  const [locationId, setLocationId] = useState<string>("");
  const [reasonFilter, setReasonFilter] = useState<ReasonFilter>("ALL");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ACTIVE");
  const [searchQuery, setSearchQuery] = useState("");
  const [feedbackMessage, setFeedbackMessage] = useState<string | null>(null);

  const locationsQuery = useQuery({
    queryKey: ["pricing", "locations"],
    queryFn: () => api.locations()
  });

  useEffect(() => {
    if (!locationId && locationsQuery.data && locationsQuery.data.length > 0) {
      const preferred =
        scope.locationId && locationsQuery.data.some((l) => l.locationId === scope.locationId)
          ? scope.locationId
          : locationsQuery.data[0].locationId;
      setLocationId(preferred);
    }
  }, [locationId, locationsQuery.data, scope.locationId]);

  const balancesQuery = useQuery({
    queryKey: ["pricing", "markdown", "balances", locationId],
    queryFn: () => (api.listBalances ? api.listBalances(locationId) : Promise.resolve([])),
    enabled: Boolean(locationId && api.listBalances)
  });

  const rulesQuery = useQuery({
    queryKey: ["pricing", "markdown", "rules"],
    queryFn: () =>
      api.listRules
        ? api.listRules({ kind: "EXPIRY_MARKDOWN", status: "ACTIVE" })
        : Promise.resolve([]),
    enabled: Boolean(api.listRules)
  });

  const discountsQuery = useQuery({
    queryKey: ["pricing", "markdowns", locationId, reasonFilter, statusFilter],
    queryFn: () =>
      api.listExpirySlowMovingDiscounts
        ? api.listExpirySlowMovingDiscounts(
          locationId,
          reasonFilter === "ALL" ? undefined : reasonFilter,
          statusFilter === "ALL" ? undefined : statusFilter
        )
        : Promise.resolve([]),
    enabled: Boolean(locationId && api.listExpirySlowMovingDiscounts)
  });

  const evaluateMutation = useMutation({
    mutationFn: () => api.evaluateExpirySlowMovingDiscounts(locationId, idempotencyKey.current()),
    onSuccess: (result) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["pricing", "markdowns"] });
      queryClient.invalidateQueries({ queryKey: ["pricing", "markdown", "balances"] });
      setFeedbackMessage(
        t("pricing.markdown.evaluated_success", undefined, { count: result.markdownsActive }).text
      );
    }
  });

  const activeMarkdownRules = useMemo(() => {
    const list = rulesQuery.data ?? [];
    return [...list].sort((a, b) => a.priority - b.priority);
  }, [rulesQuery.data]);

  const queueItems = useMemo<MarkdownQueueItem[]>(() => {
    const discounts = discountsQuery.data ?? [];
    const balances = balancesQuery.data ?? [];

    if (balances.length > 0) {
      // Map real inventory lot balances at this location
      const lotItems = balances
        .filter((b) => b.expiryDate && b.qtyOnHand > 0)
        .map((lot) => {
          const daysRemaining = lot.expiryDate ? daysBetween(today, lot.expiryDate) : null;
          const matchedRule = activeMarkdownRules.find(
            (rule) =>
              rule.predicate.skuId === lot.skuId &&
              rule.predicate.daysToExpiry != null &&
              daysRemaining != null &&
              daysRemaining <= rule.predicate.daysToExpiry &&
              daysRemaining >= 0
          );
          const matchedDiscount = discounts.find(
            (d) =>
              d.skuId === lot.skuId &&
              (d.stockLotId === lot.stockLotId || (d.batchNo && d.batchNo === lot.batchNo))
          );
          const tier =
            matchedDiscount?.tierOrClassification ??
            (daysRemaining != null && daysRemaining <= 7
              ? "CRITICAL"
              : daysRemaining != null && daysRemaining <= 30
                ? "WARNING"
                : "HEALTHY");

          return {
            id: lot.stockLotId,
            skuId: lot.skuId,
            skuCode: matchedDiscount?.skuCode,
            stockLotId: lot.stockLotId,
            batchNo: lot.batchNo,
            expiryDate: lot.expiryDate,
            daysRemaining,
            qtyOnHand: lot.qtyOnHand,
            condition: lot.condition || "GOOD",
            reason: matchedDiscount?.reason ?? "NEAR_EXPIRY",
            tierOrClassification: tier,
            basePrice: matchedDiscount?.basePrice,
            markdownPercent:
              matchedDiscount?.markdownPercent ??
              (matchedRule?.benefit.kind === "PERCENT_OFF" ? Number(matchedRule.benefit.value) : undefined),
            discountedPrice: matchedDiscount?.discountedPrice,
            matchedRule,
            status: matchedDiscount?.status ?? (matchedRule ? "ACTIVE" : "PENDING")
          };
        });

      // Also append slow-moving candidates from discounts query (excluding SKUs already mapped in lotItems)
      const slowMovingItems = discounts
        .filter((d) => d.reason === "SLOW_MOVING" && !lotItems.some((l) => l.skuId === d.skuId))
        .map((d, idx) => ({
          id: d.stockLotId ?? `slow_${d.skuId}_${idx}`,
          skuId: d.skuId,
          skuCode: d.skuCode,
          stockLotId: d.stockLotId,
          batchNo: d.batchNo ?? "-",
          expiryDate: d.expiryDate,
          daysRemaining: d.daysToExpiry,
          qtyOnHand: d.qtyOnHand,
          condition: "GOOD",
          reason: "SLOW_MOVING",
          tierOrClassification: d.tierOrClassification,
          basePrice: d.basePrice,
          markdownPercent: d.markdownPercent,
          discountedPrice: d.discountedPrice,
          matchedRule: undefined,
          status: d.status
        }));

      return [...lotItems, ...slowMovingItems];
    }

    // Fallback: When balancesQuery has no data (e.g. unit test mocks), build items directly from discounts
    return discounts.map((d, idx) => ({
      id: d.stockLotId ?? `${d.skuId}_${idx}`,
      skuId: d.skuId,
      skuCode: d.skuCode,
      stockLotId: d.stockLotId,
      batchNo: d.batchNo,
      expiryDate: d.expiryDate,
      daysRemaining: d.daysToExpiry,
      qtyOnHand: d.qtyOnHand,
      condition: "GOOD",
      reason: d.reason,
      tierOrClassification: d.tierOrClassification,
      basePrice: d.basePrice,
      markdownPercent: d.markdownPercent,
      discountedPrice: d.discountedPrice,
      matchedRule: undefined,
      status: d.status
    }));
  }, [balancesQuery.data, discountsQuery.data, activeMarkdownRules, today]);

  const filteredItems = useMemo<MarkdownQueueItem[]>(() => {
    let items = queueItems;

    if (reasonFilter !== "ALL") {
      items = items.filter((i) => i.reason === reasonFilter);
    }
    if (statusFilter !== "ALL") {
      items = items.filter((i) => i.status === statusFilter);
    }

    const q = searchQuery.trim().toLowerCase();
    if (!q) return items;

    return items.filter((i) => {
      const matchSku = i.skuCode ? i.skuCode.toLowerCase().includes(q) : false;
      const matchBatch = i.batchNo ? i.batchNo.toLowerCase().includes(q) : false;
      const matchCondition = i.condition ? i.condition.toLowerCase().includes(q) : false;
      const matchReason = i.reason ? i.reason.toLowerCase().includes(q) : false;
      const matchTier = i.tierOrClassification ? i.tierOrClassification.toLowerCase().includes(q) : false;
      const matchRule = i.matchedRule?.name ? i.matchedRule.name.toLowerCase().includes(q) : false;
      return matchSku || matchBatch || matchCondition || matchReason || matchTier || matchRule;
    });
  }, [queueItems, reasonFilter, statusFilter, searchQuery]);

  return (
    <main className="shell-page">
      <PageHeader icon="markdown" title={t("pricing.markdown.title").text} />
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.markdown.intro").text}</p>

      <section className="pricing-section">
        <div className="pricing-filter-bar">
          <label className="pricing-form-field pricing-location-filter-field">
            <span className="pricing-filter-label">
              {t("pricing.markdown.select_location").text}
            </span>
            <PricingSelect
              aria-label={t("pricing.markdown.select_location").text}
              value={locationId}
              onChange={setLocationId}
              disabled={locationsQuery.isLoading}
              options={
                locationsQuery.data?.map((loc) => ({
                  value: loc.locationId,
                  label: `${loc.locationCode} — ${inLocale(locale, loc.nameEn, loc.nameSi, loc.nameTa)}`
                })) ?? []
              }
            />
          </label>

          <label className="pricing-form-field">
            <span className="pricing-filter-label">{t("pricing.markdown.filter_reason").text}</span>
            <PricingSelect
              aria-label={t("pricing.markdown.filter_reason").text}
              value={reasonFilter}
              onChange={(val) => setReasonFilter(val as ReasonFilter)}
              options={[
                { value: "ALL", label: t("pricing.markdown.reason.all").text },
                { value: "NEAR_EXPIRY", label: t("pricing.markdown.reason.near_expiry").text },
                { value: "SLOW_MOVING", label: t("pricing.markdown.reason.slow_moving").text }
              ]}
            />
          </label>

          <label className="pricing-form-field">
            <span className="pricing-filter-label">{t("pricing.markdown.filter_status").text}</span>
            <PricingSelect
              aria-label={t("pricing.markdown.filter_status").text}
              value={statusFilter}
              onChange={(val) => setStatusFilter(val as StatusFilter)}
              options={[
                { value: "ACTIVE", label: t("pricing.markdown.status.active").text },
                { value: "ALL", label: t("pricing.markdown.status.all").text }
              ]}
            />
          </label>

          <label className="pricing-form-field pricing-markdown-search-field">
            <span className="pricing-filter-label">
              {t("pricing.markdown.search_label").text}
            </span>
            <div className="pricing-markdown-search-wrapper">
              <svg
                className="pricing-markdown-search-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                type="search"
                className="pricing-markdown-search-input"
                placeholder={t("pricing.markdown.search_placeholder").text}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Escape") {
                    setSearchQuery("");
                  }
                }}
                aria-label={t("pricing.markdown.search_placeholder").text}
              />
              {searchQuery && (
                <button
                  type="button"
                  className="pricing-markdown-search-clear"
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    setSearchQuery("");
                  }}
                  aria-label={t("pricing.search.clear").text}
                  title={t("pricing.search.clear").text}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                </button>
              )}
            </div>
          </label>

          <div className="pricing-markdown-action-cell">
            <button
              type="button"
              className="btn btn--primary"
              disabled={!locationId || evaluateMutation.isPending}
              onClick={() => evaluateMutation.mutate()}
            >
              {evaluateMutation.isPending
                ? t("pricing.markdown.evaluating").text
                : t("pricing.markdown.evaluate").text}
            </button>
          </div>
        </div>

        {feedbackMessage && (
          <div className="pricing-alert-box pricing-alert-box--success pricing-markdown-feedback">
            <p>{feedbackMessage}</p>
          </div>
        )}

        {(discountsQuery.isLoading || balancesQuery.isLoading) && (
          <p>{t("pricing.list.loading").text}</p>
        )}
        {discountsQuery.isError && (
          <p role="alert">{errorText(discountsQuery.error, t("pricing.error.generic").text)}</p>
        )}

        {filteredItems.length === 0 &&
          !discountsQuery.isLoading &&
          !balancesQuery.isLoading && (
            <div className="modern-empty-state">{t("pricing.markdown.empty").text}</div>
          )}

        {filteredItems.length > 0 && (
          <div className="modern-table-card pricing-markdown-table-card">
            <div className="modern-table-scroll pricing-markdown-table-scroll">
              <table className="modern-table pricing-markdown-table">
                <colgroup>
                  <col className="pricing-col-item" />
                  <col className="pricing-col-reason" />
                  <col className="pricing-col-classification" />
                  <col className="pricing-col-batch" />
                  <col className="pricing-col-expiry" />
                  <col className="pricing-col-days" />
                  <col className="pricing-col-qty" />
                  <col className="pricing-col-condition" />
                  <col className="pricing-col-rule" />
                  <col className="pricing-col-price" />
                  <col className="pricing-col-status" />
                </colgroup>
                <thead>
                  <tr>
                    <th className="pricing-th-item">{t("pricing.markdown.column.item").text}</th>
                    <th className="pricing-th-reason">{t("pricing.markdown.column.reason").text}</th>
                    <th className="pricing-th-classification">
                      {t("pricing.markdown.column.classification").text}
                    </th>
                    <th className="pricing-th-batch">{t("pricing.markdown.column.batch").text}</th>
                    <th className="pricing-th-expiry">{t("pricing.markdown.column.expiry").text}</th>
                    <th className="pricing-th-days">
                      {t("pricing.markdown.column.days_remaining").text}
                    </th>
                    <th className="pricing-th-qty">{t("pricing.markdown.column.qty_on_hand").text}</th>
                    <th className="pricing-th-condition">
                      {t("pricing.markdown.column.condition").text}
                    </th>
                    <th className="pricing-th-rule">
                      {t("pricing.markdown.column.applicable_rule").text}
                    </th>
                    <th className="pricing-th-price">
                      {t("pricing.markdown.column.discounted_price").text}
                    </th>
                    <th className="pricing-th-status">{t("pricing.markdown.column.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredItems.map((item) => (
                    <tr key={item.id}>
                      <td className="pricing-cell-item">
                        <SkuName skuId={item.skuId} />
                      </td>
                      <td className="pricing-cell-reason">
                        <StateChip
                          state={item.reason === "NEAR_EXPIRY" ? "alert" : "issued"}
                          label={
                            item.reason === "NEAR_EXPIRY"
                              ? t("pricing.markdown.reason.near_expiry").text
                              : t("pricing.markdown.reason.slow_moving").text
                          }
                        />
                      </td>
                      <td className="pricing-cell-classification">
                        <strong>{item.tierOrClassification}</strong>
                      </td>
                      <td className="pricing-cell-batch">
                        <span className="pricing-markdown-batch">{item.batchNo ?? "-"}</span>
                      </td>
                      <td className="pricing-cell-expiry">
                        <span className="pricing-markdown-date">
                          {item.expiryDate ? formatDate(item.expiryDate) : "-"}
                        </span>
                      </td>
                      <td className="pricing-cell-days">
                        {item.daysRemaining != null ? (
                          <DaysBadge days={item.daysRemaining} />
                        ) : (
                          <span className="pricing-muted">-</span>
                        )}
                      </td>
                      <td className="pricing-cell-qty">{item.qtyOnHand}</td>
                      <td className="pricing-cell-condition">
                        <StateChip
                          state={item.condition === "GOOD" ? "issued" : "alert"}
                          label={item.condition}
                        />
                      </td>
                      <td className="pricing-cell-rule">
                        {item.matchedRule ? (
                          <div className="pricing-markdown-rule-badge">
                            <StateChip state="issued" label={item.matchedRule.name} />
                            <MarkdownBenefit benefit={item.matchedRule.benefit} />
                          </div>
                        ) : item.markdownPercent != null ? (
                          <div className="pricing-markdown-rule-badge">
                            <StateChip state="issued" label={`${item.markdownPercent}% off`} />
                          </div>
                        ) : (
                          <div className="pricing-header-row">
                            <span className="pricing-muted">{t("pricing.markdown.no_rule").text}</span>
                            <Link to="/pricing/rules" className="btn btn--secondary btn--sm">
                              {t("pricing.markdown.author_rule").text}
                            </Link>
                          </div>
                        )}
                      </td>
                      <td className="pricing-cell-price">
                        {item.discountedPrice != null ? (
                          <div>
                            <strong className="pricing-markdown-discounted-price">
                              <MoneyDisplay amount={item.discountedPrice} />
                            </strong>
                            {item.basePrice != null && (
                              <div className="pricing-markdown-original-price">
                                <MoneyDisplay amount={item.basePrice} />
                              </div>
                            )}
                          </div>
                        ) : (
                          <span className="pricing-muted">-</span>
                        )}
                      </td>
                      <td className="pricing-cell-status">
                        <StateChip
                          state={item.status === "ACTIVE" ? "issued" : "draft"}
                          label={item.status}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

function DaysBadge({ days }: { days: number }) {
  const t = useT();
  if (days < 0) {
    return <StateChip state="void" label={t("pricing.markdown.expired").text} />;
  }
  if (days <= 7) {
    return (
      <span className="pricing-alert-text pricing-markdown-days-urgent">
        <strong>{days}</strong> {t("pricing.rules.days").text}
      </span>
    );
  }
  return (
    <span className="pricing-markdown-days-normal">
      <strong>{days}</strong> {t("pricing.rules.days").text}
    </span>
  );
}

function MarkdownBenefit({ benefit }: { benefit: Rule["benefit"] }) {
  const t = useT();
  if (benefit.kind === "PERCENT_OFF") {
    return (
      <span className="pricing-markdown-benefit">
        ({t("pricing.rules.benefit.percent_off", undefined, { percent: benefit.value }).text})
      </span>
    );
  }
  if (benefit.kind === "FIXED_PRICE") {
    return (
      <span className="pricing-markdown-benefit">
        ({t("pricing.rules.benefit.fixed").text} <MoneyDisplay amount={benefit.value} />)
      </span>
    );
  }
  if (benefit.kind === "AMOUNT_OFF") {
    return (
      <span className="pricing-markdown-benefit">
        (
        {
          t("pricing.rules.benefit.amount_off", undefined, {
            amount: formatMoneyDigits(benefit.value)?.digits ?? benefit.value
          }).text
        }
        )
      </span>
    );
  }
  return <span>({benefit.value})</span>;
}
