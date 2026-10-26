import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale } from "../../shell/i18n/localName";
import { businessToday, formatMoneyDigits, useFormatDate } from "../../shell/i18n/formats";
import { useScope } from "../../shell/scope/useScope";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { Rule } from "./pricingApi";
import { errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import { PricingSelect } from "./PricingSelect";
import "./pricing.css";

function daysBetween(fromIsoDate: string, toIsoDate: string): number {
  const fromMs = Date.parse(fromIsoDate);
  const toMs = Date.parse(toIsoDate);
  return Math.round((toMs - fromMs) / (1000 * 60 * 60 * 24));
}

/**
 * The markdown queue (/pricing/markdown; 23A section 8; doc 23 section 4.3; M3-10).
 * Joins M5 near-expiry lot balance data with active M3 EXPIRY_MARKDOWN rules.
 */
export function MarkdownQueuePage() {
  const t = useT();
  const { locale } = useIntl();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const scope = useScope();
  const today = businessToday();

  const [locationId, setLocationId] = useState<string>("");

  const locationsQuery = useQuery({
    queryKey: ["pricing", "locations"],
    queryFn: () => api.locations()
  });

  useEffect(() => {
    if (!locationId && locationsQuery.data && locationsQuery.data.length > 0) {
      const preferred = scope.locationId && locationsQuery.data.some((l) => l.locationId === scope.locationId)
        ? scope.locationId
        : locationsQuery.data[0].locationId;
      setLocationId(preferred);
    }
  }, [locationId, locationsQuery.data, scope.locationId]);

  const balancesQuery = useQuery({
    queryKey: ["pricing", "markdown", "balances", locationId],
    queryFn: () => api.listBalances(locationId),
    enabled: Boolean(locationId)
  });

  const rulesQuery = useQuery({
    queryKey: ["pricing", "markdown", "rules"],
    queryFn: () => api.listRules({ kind: "EXPIRY_MARKDOWN", status: "ACTIVE" })
  });

  const activeMarkdownRules = useMemo(() => {
    const list = rulesQuery.data ?? [];
    return [...list].sort((a, b) => a.priority - b.priority);
  }, [rulesQuery.data]);

  const [searchQuery, setSearchQuery] = useState("");

  const queueItems = useMemo(() => {
    const balances = balancesQuery.data ?? [];
    return balances
      .filter((b) => b.expiryDate && b.qtyOnHand > 0)
      .map((lot) => {
        const daysRemaining = daysBetween(today, lot.expiryDate!);
        const matchedRule = activeMarkdownRules.find(
          (rule) =>
            rule.predicate.skuId === lot.skuId &&
            rule.predicate.daysToExpiry != null &&
            daysRemaining <= rule.predicate.daysToExpiry &&
            daysRemaining >= 0
        );
        return {
          lot,
          daysRemaining,
          matchedRule
        };
      })
      .sort((a, b) => a.daysRemaining - b.daysRemaining);
  }, [balancesQuery.data, today, activeMarkdownRules]);

  const filteredItems = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return queueItems;
    return queueItems.filter(({ lot, daysRemaining, matchedRule }) => {
      const matchBatch = lot.batchNo ? lot.batchNo.toLowerCase().includes(q) : false;
      const matchCondition = lot.condition ? lot.condition.toLowerCase().includes(q) : false;
      const matchRule = matchedRule?.name ? matchedRule.name.toLowerCase().includes(q) : false;
      const matchSku = lot.skuId ? lot.skuId.toLowerCase().includes(q) : false;
      const matchExpiry = lot.expiryDate ? lot.expiryDate.toLowerCase().includes(q) : false;
      const matchDays = String(daysRemaining).includes(q);
      const matchQty = String(lot.qtyOnHand).includes(q);
      return matchBatch || matchCondition || matchRule || matchSku || matchExpiry || matchDays || matchQty;
    });
  }, [queueItems, searchQuery]);

  return (
    <main className="shell-page">
      <PageHeader icon="markdown" title={t("pricing.markdown.title").text} />
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.markdown.intro").text}</p>

      <section className="pricing-section">
        <div className="pricing-filter-bar">
          <label className="pricing-form-field">
            <span>{t("pricing.markdown.select_location").text}</span>
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

          <label className="pricing-form-field pricing-markdown-search-field">
            <span style={{ color: "var(--color-brand-dark)", fontWeight: "var(--font-weight-strong)" }}>
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
        </div>

        {balancesQuery.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {balancesQuery.isError && (
          <p role="alert">{errorText(balancesQuery.error, t("pricing.error.generic").text)}</p>
        )}
        {queueItems.length === 0 && !balancesQuery.isLoading && (
          <div className="modern-empty-state">{t("pricing.markdown.empty").text}</div>
        )}
        {queueItems.length > 0 && filteredItems.length === 0 && !balancesQuery.isLoading && (
          <div className="modern-empty-state">{t("pricing.markdown.no_match").text}</div>
        )}

        {filteredItems.length > 0 && (
          <div className="modern-table-card pricing-markdown-table-card">
            <div className="modern-table-scroll pricing-markdown-table-scroll">
              <table className="modern-table pricing-markdown-table">
                <colgroup>
                  <col className="pricing-col-batch" />
                  <col className="pricing-col-expiry" />
                  <col className="pricing-col-days" />
                  <col className="pricing-col-qty" />
                  <col className="pricing-col-condition" />
                  <col className="pricing-col-rule" />
                </colgroup>
                <thead>
                  <tr>
                    <th className="pricing-th-batch">{t("pricing.markdown.column.batch").text}</th>
                    <th className="pricing-th-expiry">{t("pricing.markdown.column.expiry").text}</th>
                    <th className="pricing-th-days">{t("pricing.markdown.column.days_remaining").text}</th>
                    <th className="pricing-th-qty">{t("pricing.markdown.column.qty_on_hand").text}</th>
                    <th className="pricing-th-condition">{t("pricing.markdown.column.condition").text}</th>
                    <th className="pricing-th-rule">{t("pricing.markdown.column.applicable_rule").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredItems.map(({ lot, daysRemaining, matchedRule }) => (
                    <tr key={lot.stockLotId}>
                      <td className="pricing-cell-batch">
                        <span className="pricing-markdown-batch">{lot.batchNo}</span>
                      </td>
                      <td className="pricing-cell-expiry">
                        <span className="pricing-markdown-date">{formatDate(lot.expiryDate!)}</span>
                      </td>
                      <td className="pricing-cell-days">
                        <DaysBadge days={daysRemaining} />
                      </td>
                      <td className="pricing-cell-qty">{lot.qtyOnHand}</td>
                      <td className="pricing-cell-condition">
                        <StateChip
                          state={lot.condition === "GOOD" ? "issued" : "alert"}
                          label={lot.condition}
                        />
                      </td>
                      <td className="pricing-cell-rule">
                        {matchedRule ? (
                          <div className="pricing-markdown-rule-badge">
                            <StateChip state="issued" label={matchedRule.name} />
                            <MarkdownBenefit benefit={matchedRule.benefit} />
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
        ({t("pricing.rules.benefit.amount_off", undefined, { amount: formatMoneyDigits(benefit.value)?.digits ?? benefit.value }).text})
      </span>
    );
  }
  return <span>({benefit.value})</span>;
}
