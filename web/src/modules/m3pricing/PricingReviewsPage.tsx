import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { ReviewItem } from "./pricingApi";
import { SkuName } from "./SkuPicker";
import { errorText } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

/**
 * Exceptions and reviews screen (/pricing/reviews; doc 23 section 4.3; PRC-01).
 * Shows audit-recorded pricing review events (BATCH_MRP_BELOW_LIST, TRADE_ABOVE_MRP, LIST_ABOVE_CEILING_AT_GAZETTE)
 * in the caller's entity scope.
 */
export function PricingReviewsPage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const canView = useHasPermission("prc.review.view");

  const [kindFilter, setKindFilter] = useState<string>("ALL");

  const reviewsQuery = useQuery({
    queryKey: ["pricing", "reviews", kindFilter],
    queryFn: () =>
      api.listReviews(
        kindFilter !== "ALL" ? (kindFilter as ReviewItem["kind"]) : undefined
      ),
    enabled: canView
  });

  return (
    <main className="shell-page">
      <h1>{t("pricing.reviews.title").text}</h1>
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.reviews.intro").text}</p>

      <section className="pricing-section">
        <div className="pricing-filter-bar">
          <label className="pricing-form-field">
            <span className="pricing-filter-label">{t("pricing.reviews.filter_kind").text}</span>
            <select
              aria-label={t("pricing.reviews.filter_kind").text}
              value={kindFilter}
              onChange={(e) => setKindFilter(e.target.value)}
            >
              <option value="ALL">{t("pricing.reviews.kind.ALL").text}</option>
              <option value="BATCH_MRP_BELOW_LIST">{t("pricing.reviews.kind.BATCH_MRP_BELOW_LIST").text}</option>
              <option value="TRADE_ABOVE_MRP">{t("pricing.reviews.kind.TRADE_ABOVE_MRP").text}</option>
              <option value="LIST_ABOVE_CEILING_AT_GAZETTE">{t("pricing.reviews.kind.LIST_ABOVE_CEILING_AT_GAZETTE").text}</option>
            </select>
          </label>
        </div>

        {reviewsQuery.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {reviewsQuery.isError && <p role="alert">{errorText(reviewsQuery.error, t("pricing.error.generic").text)}</p>}
        {reviewsQuery.data?.length === 0 && (
          <div className="modern-empty-state">{t("pricing.reviews.empty").text}</div>
        )}

        {reviewsQuery.data && reviewsQuery.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.reviews.column.occurred_at").text}</th>
                    <th>{t("pricing.reviews.column.kind").text}</th>
                    <th>{t("pricing.reviews.column.severity").text}</th>
                    <th>{t("pricing.reviews.column.sku").text}</th>
                    <th>{t("pricing.reviews.column.details").text}</th>
                    <th>{t("pricing.reviews.column.escalated").text}</th>
                  </tr>
                </thead>
                <tbody>
                  {reviewsQuery.data.map((review) => (
                    <tr key={review.reviewId}>
                      <td>{formatDate(review.occurredAt)}</td>
                      <td>
                        <strong>{t(`pricing.reviews.kind.${review.kind}`).text}</strong>
                      </td>
                      <td>
                        <StateChip
                          state={review.severity === "REVIEW" ? "alert" : "void"}
                          label={review.severity}
                        />
                      </td>
                      <td>
                        {review.skuName || review.skuCode ? (
                          <span>
                            {review.skuCode ? `${review.skuCode} — ` : ""}
                            {review.skuName ?? ""}
                          </span>
                        ) : review.skuId ? (
                          <SkuName skuId={review.skuId} />
                        ) : (
                          <span className="pricing-muted">—</span>
                        )}
                      </td>
                      <td>
                        <ReviewDetails details={review.details} />
                      </td>
                      <td>
                        {review.escalated ? (
                          <StateChip state="alert" label={t("pricing.reviews.escalated_yes").text} />
                        ) : (
                          <span className="pricing-muted">{t("pricing.reviews.escalated_no").text}</span>
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

function ReviewDetails({ details }: { details: Record<string, unknown> | null | undefined }) {
  if (!details || Object.keys(details).length === 0) {
    return <span className="pricing-muted">—</span>;
  }
  return (
    <div className="pricing-review-details">
      {Object.entries(details).map(([k, v]) => (
        <span key={k} className="pricing-detail-pill">
          <strong>{k}:</strong> {String(v)}
        </span>
      ))}
    </div>
  );
}
