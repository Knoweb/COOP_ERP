import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useHasPermission } from "../../shell/auth/permissions";
import { useInventoryApi, type ExpiryAlertItem, type SlowMovingStock } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { CustomSelect } from "./CustomSelect";
import { SkuLabel } from "./SkuLabel";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";

type TabType = "feed" | "slow";
type UrgencyTierFilter = "ALL" | "CRITICAL" | "WARNING" | "APPROACHING" | "EXPIRED";

export function ExpiryListPage() {
  const t = useT();
  const api = useInventoryApi();
  const formatDate = useFormatDate();
  const queryClient = useQueryClient();
  const canScan = useHasPermission("inv.expiry.scan");

  const [locationId, setLocationId] = useState("");
  const [activeTab, setActiveTab] = useState<TabType>("feed");
  const [tierFilter, setTierFilter] = useState<UrgencyTierFilter>("ALL");
  const [scanMessage, setScanMessage] = useState<string | null>(null);

  // Queries for Near-Expiry and Slow-Moving
  const feedQuery = useQuery({
    queryKey: ["inventory", "expiry-feed", locationId, tierFilter],
    queryFn: () =>
      api.expiryFeed(locationId, undefined, tierFilter === "ALL" ? undefined : tierFilter),
    enabled: locationId !== "" && activeTab === "feed"
  });

  // Query across all tiers to compute summary KPIs
  const allFeedQuery = useQuery({
    queryKey: ["inventory", "expiry-feed-all", locationId],
    queryFn: () => api.expiryFeed(locationId, undefined, undefined),
    enabled: locationId !== ""
  });

  const slowQuery = useQuery({
    queryKey: ["inventory", "slow-moving", locationId],
    queryFn: () => api.slowMovingStock(locationId),
    enabled: locationId !== ""
  });

  // Scan & emit alerts mutation
  const scanMutation = useMutation({
    mutationFn: async () => {
      setScanMessage(null);
      const idempotencyKey = crypto.randomUUID();
      return api.scanExpiryAlerts(locationId, idempotencyKey);
    },
    onSuccess: (result) => {
      setScanMessage(
        t("inventory.expiry.scan_success", undefined, {
          nearExpiry: String(result.nearExpiryAlertsEmitted),
          slowMoving: String(result.slowMovingAlertsEmitted)
        }).text
      );
      queryClient.invalidateQueries({ queryKey: ["inventory", "expiry-feed"] });
      queryClient.invalidateQueries({ queryKey: ["inventory", "expiry-feed-all"] });
      queryClient.invalidateQueries({ queryKey: ["inventory", "slow-moving"] });
    }
  });

  // Calculate KPIs
  const allFeedItems = allFeedQuery.data ?? [];
  const criticalCount = allFeedItems.filter((i) => i.urgencyTier === "CRITICAL").length;
  const warningCount = allFeedItems.filter((i) => i.urgencyTier === "WARNING").length;
  const expiredCount = allFeedItems.filter((i) => i.urgencyTier === "EXPIRED").length;
  const slowCount = slowQuery.data?.length ?? 0;

  const feedItems: ExpiryAlertItem[] = feedQuery.data ?? [];
  const slowItems: SlowMovingStock[] = slowQuery.data ?? [];

  const tierChipState = (tier: string) => {
    switch (tier) {
      case "CRITICAL":
        return "alert" as const;
      case "WARNING":
        return "disputed" as const;
      case "EXPIRED":
        return "void" as const;
      default:
        return "issued" as const;
    }
  };

  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/inventory">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("inventory.back").text}
        </Link>
      </div>
      <PageHeader icon="expiry" title={t("inventory.expiry.title").text} />
      <p className="modern-intro-text">{t("inventory.expiry.intro").text}</p>

      <section className="modern-filter-panel modern-filter-panel--stock">
        <LocationPicker value={locationId} onChange={setLocationId} />
      </section>

      {locationId !== "" && (
        <>
          {/* Summary KPI Cards */}
          <section className="expiry-kpi-grid">
            <div className="expiry-kpi-card expiry-kpi-card--critical">
              <span className="expiry-kpi-title">{t("inventory.expiry.kpi_critical").text}</span>
              <span className="expiry-kpi-count">{criticalCount}</span>
            </div>
            <div className="expiry-kpi-card expiry-kpi-card--warning">
              <span className="expiry-kpi-title">{t("inventory.expiry.kpi_warning").text}</span>
              <span className="expiry-kpi-count">{warningCount}</span>
            </div>
            <div className="expiry-kpi-card expiry-kpi-card--slow">
              <span className="expiry-kpi-title">{t("inventory.expiry.kpi_slow").text}</span>
              <span className="expiry-kpi-count">{slowCount}</span>
            </div>
            <div className="expiry-kpi-card expiry-kpi-card--expired">
              <span className="expiry-kpi-title">{t("inventory.expiry.kpi_expired").text}</span>
              <span className="expiry-kpi-count">{expiredCount}</span>
            </div>
          </section>

          {/* Tab Navigation */}
          <nav className="expiry-tabs" aria-label={t("inventory.expiry.tab_views_label").text}>
            <button
              type="button"
              className={`expiry-tab-button ${activeTab === "feed" ? "expiry-tab-button--active" : ""}`}
              onClick={() => setActiveTab("feed")}
            >
              {t("inventory.expiry.tab_alerts").text}
            </button>
            <button
              type="button"
              className={`expiry-tab-button ${activeTab === "slow" ? "expiry-tab-button--active" : ""}`}
              onClick={() => setActiveTab("slow")}
            >
              {t("inventory.expiry.tab_slow_moving").text}
            </button>
          </nav>

          {/* Action Row & Filters */}
          <div className="expiry-controls-row">
            {activeTab === "feed" ? (
              <div className="expiry-tier-filter">
                <label htmlFor="urgency-tier-filter" className="field-label">
                  {t("inventory.expiry.filter_tier").text}:
                </label>
                <CustomSelect
                  id="urgency-tier-filter"
                  className="expiry-tier-select"
                  ariaLabel={t("inventory.expiry.filter_tier").text}
                  value={tierFilter}
                  onChange={(val) => setTierFilter(val as UrgencyTierFilter)}
                  options={[
                    { value: "ALL", label: t("inventory.expiry.filter_all").text },
                    { value: "CRITICAL", label: t("inventory.expiry.filter_critical").text },
                    { value: "WARNING", label: t("inventory.expiry.filter_warning").text },
                    { value: "APPROACHING", label: t("inventory.expiry.filter_approaching").text },
                    { value: "EXPIRED", label: t("inventory.expiry.filter_expired").text }
                  ]}
                />
              </div>
            ) : (
              <div />
            )}

            {canScan && (
              <button
                type="button"
                className="button button--secondary"
                disabled={scanMutation.isPending}
                onClick={() => scanMutation.mutate()}
              >
                {scanMutation.isPending
                  ? t("inventory.expiry.scanning").text
                  : t("inventory.expiry.scan_button").text}
              </button>
            )}
          </div>

          {/* Scan result banner */}
          {scanMessage && (
            <div className="expiry-scan-banner" role="status">
              {scanMessage}
            </div>
          )}

          {scanMutation.isError && (
            <p role="alert" className="error-message">
              {errorText(scanMutation.error, t("inventory.error.generic").text)}
            </p>
          )}

          {/* TAB 1: Near-Expiry Alerts Feed */}
          {activeTab === "feed" && (
            <>
              {feedQuery.isLoading && <p>{t("inventory.loading").text}</p>}
              {feedQuery.isError && (
                <p role="alert">{errorText(feedQuery.error, t("inventory.error.generic").text)}</p>
              )}
              {!feedQuery.isLoading && feedItems.length === 0 && (
                <p>{t("inventory.expiry.none").text}</p>
              )}

              {feedItems.length > 0 && (
                <section className="modern-table-card">
                  <div className="modern-table-scroll">
                    <table className="modern-table stock-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.column.item").text}</th>
                          <th>{t("inventory.column.batch").text}</th>
                          <th>{t("inventory.column.expiry").text}</th>
                          <th className="numeric-cell">{t("inventory.expiry.days").text}</th>
                          <th className="numeric-cell">{t("inventory.column.on_hand").text}</th>
                          <th>{t("inventory.repack.column.status").text}</th>
                          <th>{t("inventory.expiry.alert_active").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {feedItems.map((item) => (
                          <tr key={item.stockLotId}>
                            <td>
                              <SkuLabel skuId={item.skuId} />
                            </td>
                            <td>
                              {isSyntheticBatchNo(item.batchNo)
                                ? t("inventory.field.batch_not_tracked").text
                                : item.batchNo}
                            </td>
                            <td>{formatDate(item.expiryDate)}</td>
                            <td className="numeric-cell">{item.daysToExpiry}</td>
                            <td className="numeric-cell">{item.qtyOnHand}</td>
                            <td>
                              <StateChip
                                state={tierChipState(item.urgencyTier)}
                                label={t(`inventory.expiry.tier.${item.urgencyTier}`).text}
                              />
                            </td>
                            <td>
                              {item.alertActive ? (
                                <StateChip
                                  state="alert"
                                  label={t("inventory.expiry.alert_active").text}
                                />
                              ) : (
                                <StateChip state="issued" label="—" />
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}
            </>
          )}

          {/* TAB 2: Slow-Moving Stock */}
          {activeTab === "slow" && (
            <>
              <p className="modern-intro-text">{t("inventory.slow.intro").text}</p>
              {slowQuery.isLoading && <p>{t("inventory.loading").text}</p>}
              {slowQuery.isError && (
                <p role="alert">{errorText(slowQuery.error, t("inventory.error.generic").text)}</p>
              )}
              {!slowQuery.isLoading && slowItems.length === 0 && (
                <p>{t("inventory.slow.none").text}</p>
              )}

              {slowItems.length > 0 && (
                <section className="modern-table-card">
                  <div className="modern-table-scroll">
                    <table className="modern-table stock-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.slow.column.item").text}</th>
                          <th className="numeric-cell">{t("inventory.slow.column.on_hand").text}</th>
                          <th className="numeric-cell">{t("inventory.slow.column.inactive_days").text}</th>
                          <th className="numeric-cell">{t("inventory.slow.column.sales").text}</th>
                          <th className="numeric-cell">{t("inventory.slow.column.dos").text}</th>
                          <th>{t("inventory.slow.column.classification").text}</th>
                          <th>{t("inventory.expiry.alert_active").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {slowItems.map((item) => (
                          <tr key={item.skuId}>
                            <td>
                              <SkuLabel skuId={item.skuId} />
                            </td>
                            <td className="numeric-cell">{item.qtyOnHand}</td>
                            <td className="numeric-cell">{item.daysWithoutSale}</td>
                            <td className="numeric-cell">{item.salesInWindow}</td>
                            <td className="numeric-cell">{item.daysOfSupply}</td>
                            <td>
                              <StateChip
                                state={item.classification === "STAGNANT" ? "void" : "alert"}
                                label={t(`inventory.slow.classification.${item.classification}`).text}
                              />
                            </td>
                            <td>
                              {item.alertActive ? (
                                <StateChip
                                  state="alert"
                                  label={t("inventory.expiry.alert_active").text}
                                />
                              ) : (
                                <StateChip state="issued" label="—" />
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}
            </>
          )}
        </>
      )}
    </main>
  );
}
