import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale } from "../../shell/i18n/localName";
import { businessToday, formatMoneyDigits, useFormatDate } from "../../shell/i18n/formats";
import { useScope } from "../../shell/scope/useScope";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { Rule } from "./pricingApi";
import { SkuName } from "./SkuPicker";
import { errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
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

  return (
    <main className="shell-page">
      <h1>{t("pricing.markdown.title").text}</h1>
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.markdown.intro").text}</p>

      <section className="pricing-section">
        <div className="pricing-filter-bar">
          <label className="pricing-form-field">
            <span>{t("pricing.markdown.select_location").text}</span>
            <select
              aria-label={t("pricing.markdown.select_location").text}
              value={locationId}
              onChange={(e) => setLocationId(e.target.value)}
              disabled={locationsQuery.isLoading}
            >
              {locationsQuery.data?.map((loc) => (
                <option key={loc.locationId} value={loc.locationId}>
                  {loc.locationCode} — {inLocale(locale, loc.nameEn, loc.nameSi, loc.nameTa)}
                </option>
              ))}
            </select>
          </label>
        </div>

        {balancesQuery.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {balancesQuery.isError && (
          <p role="alert">{errorText(balancesQuery.error, t("pricing.error.generic").text)}</p>
        )}
        {queueItems.length === 0 && !balancesQuery.isLoading && (
          <div className="modern-empty-state">{t("pricing.markdown.empty").text}</div>
        )}

        {queueItems.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.markdown.column.item").text}</th>
                    <th>{t("pricing.markdown.column.batch").text}</th>
                    <th>{t("pricing.markdown.column.expiry").text}</th>
                    <th>{t("pricing.markdown.column.days_remaining").text}</th>
                    <th>{t("pricing.markdown.column.qty_on_hand").text}</th>
                    <th>{t("pricing.markdown.column.condition").text}</th>
                    <th>{t("pricing.markdown.column.applicable_rule").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {queueItems.map(({ lot, daysRemaining, matchedRule }) => (
                    <tr key={lot.stockLotId}>
                      <td>
                        <SkuName skuId={lot.skuId} />
                      </td>
                      <td>
                        <strong>{lot.batchNo}</strong>
                      </td>
                      <td>{formatDate(lot.expiryDate!)}</td>
                      <td>
                        <DaysBadge days={daysRemaining} />
                      </td>
                      <td>{lot.qtyOnHand}</td>
                      <td>
                        <StateChip
                          state={lot.condition === "GOOD" ? "issued" : "alert"}
                          label={lot.condition}
                        />
                      </td>
                      <td>
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
      <span className="pricing-alert-text">
        <strong>{days}</strong> {t("pricing.rules.days").text}
      </span>
    );
  }
  return (
    <span>
      {days} {t("pricing.rules.days").text}
    </span>
  );
}

function MarkdownBenefit({ benefit }: { benefit: Rule["benefit"] }) {
  const t = useT();
  if (benefit.kind === "PERCENT_OFF") {
    return <span>({t("pricing.rules.benefit.percent_off", undefined, { percent: benefit.value }).text})</span>;
  }
  if (benefit.kind === "FIXED_PRICE") {
    return (
      <span>
        ({t("pricing.rules.benefit.fixed").text} <MoneyDisplay amount={benefit.value} />)
      </span>
    );
  }
  if (benefit.kind === "AMOUNT_OFF") {
    return (
      <span>
        ({t("pricing.rules.benefit.amount_off", undefined, { amount: formatMoneyDigits(benefit.value)?.digits ?? benefit.value }).text})
      </span>
    );
  }
  return <span>({benefit.value})</span>;
}
