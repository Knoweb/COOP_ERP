import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useIntl } from "react-intl";
import { useT } from "../../shell/i18n/useT";
import { inLocale } from "../../shell/i18n/localName";
import { useFormatDate } from "../../shell/i18n/formats";
import { useScope } from "../../shell/scope/useScope";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { ExpirySlowMovingDiscount } from "./pricingApi";
import { SkuName } from "./SkuPicker";
import { errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import { PricingSelect } from "./PricingSelect";
import "./pricing.css";

type ReasonFilter = "ALL" | "NEAR_EXPIRY" | "SLOW_MOVING";
type StatusFilter = "ALL" | "ACTIVE";

/**
 * The markdown queue (/pricing/markdown; 23A section 8; PGL-M1-07).
 * Displays authoritative active near-expiry and slow-moving discount markdowns
 * calculated from inventory facts and configurable rules.
 */
export function MarkdownQueuePage() {
  const t = useT();
  const { locale } = useIntl();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const scope = useScope();
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();

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

  const discountsQuery = useQuery({
    queryKey: ["pricing", "markdowns", locationId, reasonFilter, statusFilter],
    queryFn: () =>
      api.listExpirySlowMovingDiscounts(
        locationId,
        reasonFilter === "ALL" ? undefined : reasonFilter,
        statusFilter === "ALL" ? undefined : statusFilter
      ),
    enabled: Boolean(locationId)
  });

  const evaluateMutation = useMutation({
    mutationFn: () => api.evaluateExpirySlowMovingDiscounts(locationId, idempotencyKey.current()),
    onSuccess: (result) => {
      idempotencyKey.next();
      queryClient.invalidateQueries({ queryKey: ["pricing", "markdowns"] });
      setFeedbackMessage(
        t("pricing.markdown.evaluated_success", undefined, { count: result.markdownsActive }).text
      );
    }
  });

  const discounts: ExpirySlowMovingDiscount[] = useMemo(
    () => discountsQuery.data ?? [],
    [discountsQuery.data]
  );

  const filteredItems = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return discounts;
    return discounts.filter((item) => {
      const matchSku = item.skuCode ? item.skuCode.toLowerCase().includes(q) : false;
      const matchBatch = item.batchNo ? item.batchNo.toLowerCase().includes(q) : false;
      const matchReason = item.reason ? item.reason.toLowerCase().includes(q) : false;
      const matchTier = item.tierOrClassification ? item.tierOrClassification.toLowerCase().includes(q) : false;
      return matchSku || matchBatch || matchReason || matchTier;
    });
  }, [discounts, searchQuery]);

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

          <label className="pricing-form-field">
            <span>{t("pricing.markdown.filter_reason").text}</span>
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
            <span>{t("pricing.markdown.filter_status").text}</span>
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
            <span>{t("pricing.markdown.search_label").text}</span>
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

        {discountsQuery.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {discountsQuery.isError && (
          <p role="alert">{errorText(discountsQuery.error, t("pricing.error.generic").text)}</p>
        )}

        {discounts.length === 0 && !discountsQuery.isLoading && (
          <div className="modern-empty-state">{t("pricing.markdown.empty").text}</div>
        )}
        {discounts.length > 0 && filteredItems.length === 0 && !discountsQuery.isLoading && (
          <div className="modern-empty-state">{t("pricing.markdown.no_match").text}</div>
        )}

        {filteredItems.length > 0 && (
          <div className="modern-table-card pricing-markdown-table-card">
            <div className="modern-table-scroll pricing-markdown-table-scroll">
              <table className="modern-table pricing-markdown-table">
                <thead>
                  <tr>
                    <th className="pricing-th-item">{t("pricing.markdown.column.item").text}</th>
                    <th>{t("pricing.markdown.column.reason").text}</th>
                    <th>{t("pricing.markdown.column.classification").text}</th>
                    <th className="pricing-th-batch">{t("pricing.markdown.column.batch").text}</th>
                    <th className="pricing-th-qty">{t("pricing.markdown.column.qty_on_hand").text}</th>
                    <th>{t("pricing.markdown.column.base_price").text}</th>
                    <th>{t("pricing.markdown.column.discount").text}</th>
                    <th>{t("pricing.markdown.column.discounted_price").text}</th>
                    <th>{t("pricing.markdown.column.status").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredItems.map((item, idx) => (
                    <tr key={item.stockLotId ?? `${item.skuId}_${idx}`}>
                      <td className="pricing-cell-item">
                        <SkuName skuId={item.skuId} />
                      </td>
                      <td>
                        <StateChip
                          state={item.reason === "NEAR_EXPIRY" ? "alert" : "issued"}
                          label={
                            item.reason === "NEAR_EXPIRY"
                              ? t("pricing.markdown.reason.near_expiry").text
                              : t("pricing.markdown.reason.slow_moving").text
                          }
                        />
                      </td>
                      <td>
                        <strong>{item.tierOrClassification}</strong>
                      </td>
                      <td className="pricing-cell-batch">
                        {item.batchNo ? (
                          <div>
                            <span className="pricing-markdown-batch">{item.batchNo}</span>
                            {item.expiryDate && (
                              <div>
                                <span className="pricing-markdown-date">{formatDate(item.expiryDate)}</span>
                                {item.daysToExpiry != null && (
                                  <div className="pricing-markdown-days-wrapper">
                                    <DaysBadge days={item.daysToExpiry} />
                                  </div>
                                )}
                              </div>
                            )}
                          </div>
                        ) : (
                          <span className="pricing-muted">{t("pricing.markdown.sku_level").text}</span>
                        )}
                      </td>
                      <td className="pricing-cell-qty">{item.qtyOnHand}</td>
                      <td>
                        <MoneyDisplay amount={item.basePrice} />
                      </td>
                      <td>
                        <strong>{item.markdownPercent}%</strong>
                      </td>
                      <td>
                        <strong className="pricing-markdown-discounted-price">
                          <MoneyDisplay amount={item.discountedPrice} />
                        </strong>
                      </td>
                      <td>
                        <StateChip state="issued" label={item.status} />
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
