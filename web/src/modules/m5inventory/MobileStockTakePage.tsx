import { useEffect, useId, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { LocationPicker } from "./LocationPicker";
import { CustomSelect } from "./CustomSelect";
import {
  useInventoryApi,
  type StockTakeScanRequest,
  type StockTakeScanItemResponse,
  type StockTakeReviewLine
} from "./inventoryApi";
import "./inventory.css";

/**
 * Mobile Stock-Taking Client (M3-09):
 * Provides barcode scanning and manual SKU entry for warehouse and shop audits.
 * Strictly consumes M5 StockTake APIs (session, scan, batch, review, reconcile).
 * Features offline resilience: scans are client-stamped with unique scanId and
 * queued locally during connectivity loss, then safely synced via batchStockTakeScan
 * with server-side deduplication preventing double-counts.
 */
export function MobileStockTakePage() {
  const { sessionId: paramSessionId } = useParams();
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const canRecord = useHasPermission("shop.count.record");

  const openKey = useIdempotencyKey();
  const scanKey = useIdempotencyKey();
  const syncKey = useIdempotencyKey();
  const reconcileKey = useIdempotencyKey();

  const [locationId, setLocationId] = useState("");
  const [activeTab, setActiveTab] = useState<"scan" | "review">("scan");

  // Scan input fields
  const [barcodeInput, setBarcodeInput] = useState("");
  const [quantityInput, setQuantityInput] = useState("1");
  const [conditionInput, setConditionInput] = useState<"GOOD" | "DAMAGED">("GOOD");
  const [notesInput, setNotesInput] = useState("");

  // Offline queue and sync status
  const [isOnline, setIsOnline] = useState(
    typeof navigator !== "undefined" ? navigator.onLine : true
  );
  const [offlineQueue, setOfflineQueue] = useState<StockTakeScanRequest[]>([]);
  const [feedbackMessage, setFeedbackMessage] = useState<string | null>(null);
  const [duplicateNotice, setDuplicateNotice] = useState<string | null>(null);

  // Reconciliation options
  const [uncountedHandling, setUncountedHandling] = useState<"ZERO" | "SKIP">("ZERO");
  const [skipReason, setSkipReason] = useState("");

  const barcodeInputId = useId();
  const quantityInputId = useId();
  const conditionInputId = useId();
  const notesInputId = useId();
  const uncountedHandlingId = useId();
  const skipReasonId = useId();

  // Listen to network status
  useEffect(() => {
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  // 1. Session Query (either by path param or active for selected location)
  const activeSessionQuery = useQuery({
    queryKey: ["inventory", "stock-take", "active", locationId],
    queryFn: async () => {
      if (!locationId) return null;
      return api.getActiveStockTakeSession(locationId);
    },
    enabled: !!locationId && !paramSessionId
  });

  const specificSessionQuery = useQuery({
    queryKey: ["inventory", "stock-take", "session", paramSessionId],
    queryFn: async () => {
      if (!paramSessionId) return null;
      return api.getStockTakeSession(paramSessionId);
    },
    enabled: !!paramSessionId
  });

  const session = paramSessionId ? specificSessionQuery.data : activeSessionQuery.data;
  const currentSessionId = session?.sessionId ?? paramSessionId ?? "";

  // Synchronize locationId from specific session if loaded
  useEffect(() => {
    if (specificSessionQuery.data?.locationId && !locationId) {
      setLocationId(specificSessionQuery.data.locationId);
    }
  }, [specificSessionQuery.data, locationId]);

  // Load offline queue from localStorage
  useEffect(() => {
    if (!currentSessionId) return;
    try {
      const stored = localStorage.getItem(`stock_take_queue_${currentSessionId}`);
      if (stored) {
        setOfflineQueue(JSON.parse(stored));
      }
    } catch {
      // localStorage may be disabled or mocked
    }
  }, [currentSessionId]);

  // Save offline queue to localStorage
  const updateQueue = (newQueue: StockTakeScanRequest[]) => {
    setOfflineQueue(newQueue);
    if (currentSessionId) {
      try {
        localStorage.setItem(`stock_take_queue_${currentSessionId}`, JSON.stringify(newQueue));
      } catch {
        // localStorage error fallback
      }
    }
  };

  // 2. Scans List Query
  const scansQuery = useQuery({
    queryKey: ["inventory", "stock-take", "scans", currentSessionId],
    queryFn: async () => {
      if (!currentSessionId) return [];
      return api.listStockTakeScans(currentSessionId);
    },
    enabled: !!currentSessionId
  });

  // 3. Review Query
  const reviewQuery = useQuery({
    queryKey: ["inventory", "stock-take", "review", currentSessionId],
    queryFn: async () => {
      if (!currentSessionId) return null;
      return api.reviewStockTakeSession(currentSessionId);
    },
    enabled: !!currentSessionId && activeTab === "review"
  });

  // Mutation: Open session
  const openSessionMutation = useMutation({
    mutationFn: async () => {
      const key = openKey.current();
      const res = await api.openStockTakeSession({ locationId, scopeKind: "FULL" }, key);
      openKey.next();
      return res;
    },
    onSuccess: (res) => {
      setFeedbackMessage(null);
      queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take"] });
      if (res.locationId) {
        setLocationId(res.locationId);
      }
    },
    onError: (err) => {
      if (err instanceof ApiProblem) {
        openKey.next();
      }
    }
  });

  // Helper to generate a client UUID
  const generateScanId = (): string => {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    return "01900000-0000-7000-8000-" + Math.random().toString(16).substring(2, 14).padStart(12, "0");
  };

  // Mutation: Record single scan
  const recordScanMutation = useMutation({
    mutationFn: async (req: StockTakeScanRequest) => {
      const key = scanKey.current();
      const res = await api.recordStockTakeScan(currentSessionId, req, key);
      scanKey.next();
      return res;
    },
    onSuccess: (res) => {
      setBarcodeInput("");
      setQuantityInput("1");
      setNotesInput("");
      setFeedbackMessage(null);
      if (res.wasDuplicate) {
        setDuplicateNotice(t("inventory.stock_take.duplicate_detected").text);
      } else {
        setDuplicateNotice(null);
      }
      queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take", "scans", currentSessionId] });
      queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take", "active"] });
      queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take", "session"] });
    },
    onError: (err, variables) => {
      // If network failure or offline, queue locally
      if (err instanceof TypeError || !isOnline || !(err instanceof ApiProblem)) {
        updateQueue([...offlineQueue, variables]);
        setBarcodeInput("");
        setQuantityInput("1");
        setNotesInput("");
        setFeedbackMessage(t("inventory.stock_take.scan_added_offline").text);
      } else {
        scanKey.next();
      }
    }
  });

  // Mutation: Sync offline batch
  const syncBatchMutation = useMutation({
    mutationFn: async () => {
      if (offlineQueue.length === 0) return null;
      const key = syncKey.current();
      const res = await api.batchStockTakeScan(currentSessionId, { scans: offlineQueue }, key);
      syncKey.next();
      return res;
    },
    onSuccess: (res) => {
      if (res) {
        updateQueue([]);
        setFeedbackMessage(
          t("inventory.stock_take.sync_success", undefined, { count: res.processedCount }).text
        );
        if (res.duplicateCount > 0) {
          setDuplicateNotice(t("inventory.stock_take.duplicate_detected").text);
        } else {
          setDuplicateNotice(null);
        }
        queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take", "scans", currentSessionId] });
        queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take", "active"] });
        queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take", "session"] });
      }
    },
    onError: (err) => {
      if (err instanceof ApiProblem) {
        syncKey.next();
      }
    }
  });

  // Mutation: Reconcile session
  const reconcileMutation = useMutation({
    mutationFn: async () => {
      const key = reconcileKey.current();
      const res = await api.reconcileStockTakeSession(
        currentSessionId,
        {
          uncountedHandling,
          skipReason: uncountedHandling === "SKIP" ? skipReason : undefined
        },
        key
      );
      reconcileKey.next();
      return res;
    },
    onSuccess: () => {
      setFeedbackMessage(t("inventory.stock_take.reconciled_success").text);
      queryClient.invalidateQueries({ queryKey: ["inventory", "stock-take"] });
    },
    onError: (err) => {
      if (err instanceof ApiProblem) {
        reconcileKey.next();
      }
    }
  });

  // Handle Scan Form Submit
  const handleRecordScan = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedCode = barcodeInput.trim();
    const qty = parseFloat(quantityInput);
    if (!trimmedCode || isNaN(qty) || qty <= 0) return;

    const scanRequest: StockTakeScanRequest = {
      scanId: generateScanId(),
      barcode: trimmedCode,
      quantity: qty,
      condition: conditionInput,
      scannedAt: new Date().toISOString(),
      notes: notesInput.trim() || undefined
    };

    if (!isOnline) {
      updateQueue([...offlineQueue, scanRequest]);
      setBarcodeInput("");
      setQuantityInput("1");
      setNotesInput("");
      setFeedbackMessage(t("inventory.stock_take.scan_added_offline").text);
    } else {
      recordScanMutation.mutate(scanRequest);
    }
  };

  const isCounting = session?.status === "COUNTING" || session?.status === "SCHEDULED";

  return (
    <main className="shell-page mobile-stock-take-page">
      <Link className="back-link" to="/inventory/counts">
        <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        {t("inventory.stock_take.back_to_counts").text}
      </Link>

      <PageHeader
        icon="stock"
        title={t("inventory.stock_take.title").text}
        actions={
          <div className="mobile-stock-take-status-bar">
            {isOnline ? (
              <span className="mobile-stock-take-badge mobile-stock-take-badge--online">
                ● {t("inventory.stock_take.online_badge").text}
              </span>
            ) : (
              <span className="mobile-stock-take-badge mobile-stock-take-badge--offline">
                ○ {t("inventory.stock_take.offline_badge").text}
              </span>
            )}
          </div>
        }
      />

      {/* Control Navigation Links */}
      <div className="inventory-control-links">
        <Link className="action-link action-link--primary" to="/inventory/stock-take">
          <span>{t("inventory.stock_take.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/counts">
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/repacks">
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
      </div>

      {/* Location Selector */}
      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      {/* Offline Queue Notification Card */}
      {offlineQueue.length > 0 && (
        <section className="mobile-stock-take-queue-banner" role="status">
          <div className="mobile-stock-take-queue-text">
            <strong>
              {t("inventory.stock_take.queued_scans_count", undefined, {
                count: offlineQueue.length
              }).text}
            </strong>
          </div>
          <button
            type="button"
            className="action-button action-button--primary"
            onClick={() => syncBatchMutation.mutate()}
            disabled={syncBatchMutation.isPending || !currentSessionId}
          >
            {syncBatchMutation.isPending
              ? t("inventory.stock_take.syncing").text
              : t("inventory.stock_take.sync_queue_btn").text}
          </button>
        </section>
      )}

      {/* Feedback & Notice Messages */}
      {feedbackMessage && (
        <div className="mobile-stock-take-alert mobile-stock-take-alert--info" role="status">
          {feedbackMessage}
        </div>
      )}

      {duplicateNotice && (
        <div className="mobile-stock-take-alert mobile-stock-take-alert--warning" role="alert">
          {duplicateNotice}
        </div>
      )}

      {/* Location Empty Prompt */}
      {!locationId && (
        <p className="mobile-stock-take-empty-notice">
          {t("inventory.field.location").text}
        </p>
      )}

      {/* No Active Session State */}
      {locationId && !session && !activeSessionQuery.isLoading && (
        <section className="modern-table-card mobile-stock-take-card">
          <p>{t("inventory.stock_take.no_active_session").text}</p>
          {canRecord && (
            <button
              type="button"
              className="action-button action-button--primary"
              onClick={() => openSessionMutation.mutate()}
              disabled={openSessionMutation.isPending}
            >
              {openSessionMutation.isPending
                ? t("inventory.loading").text
                : t("inventory.stock_take.start_session").text}
            </button>
          )}
        </section>
      )}

      {/* Active Session Overview */}
      {session && (
        <>
          <section className="modern-table-card mobile-stock-take-session-card">
            <div className="mobile-stock-take-session-header">
              <div>
                <h2>{t("inventory.stock_take.session_active").text}</h2>
                <span className="mobile-stock-take-session-id">
                  {session.sessionId}
                </span>
              </div>
              <span className={`mobile-stock-take-chip mobile-stock-take-chip--${session.status.toLowerCase()}`}>
                {session.status}
              </span>
            </div>

            <div className="mobile-stock-take-kpi-grid">
              <div className="mobile-stock-take-kpi">
                <span className="mobile-stock-take-kpi__label">
                  {t("inventory.stock_take.expected_lots").text}
                </span>
                <span className="mobile-stock-take-kpi__value">
                  {session.expectedLotsCount}
                </span>
              </div>
              <div className="mobile-stock-take-kpi">
                <span className="mobile-stock-take-kpi__label">
                  {t("inventory.stock_take.total_scans").text}
                </span>
                <span className="mobile-stock-take-kpi__value">
                  {session.totalScansCount}
                </span>
              </div>
              <div className="mobile-stock-take-kpi">
                <span className="mobile-stock-take-kpi__label">
                  {t("inventory.stock_take.total_units").text}
                </span>
                <span className="mobile-stock-take-kpi__value">
                  {session.totalCountedUnits.toFixed(3)}
                </span>
              </div>
            </div>
          </section>

          {/* Navigation Tabs (Scan & Count vs Review Variances) */}
          <div className="mobile-stock-take-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === "scan"}
              className={`mobile-stock-take-tab ${activeTab === "scan" ? "mobile-stock-take-tab--active" : ""}`}
              onClick={() => setActiveTab("scan")}
            >
              {t("inventory.stock_take.tab_scan").text}
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === "review"}
              className={`mobile-stock-take-tab ${activeTab === "review" ? "mobile-stock-take-tab--active" : ""}`}
              onClick={() => setActiveTab("review")}
            >
              {t("inventory.stock_take.tab_review").text}
            </button>
          </div>

          {/* TAB 1: Scan & Count */}
          {activeTab === "scan" && (
            <>
              {isCounting && canRecord && (
                <section className="modern-table-card mobile-stock-take-scan-form-card">
                  <h3>{t("inventory.stock_take.scan_section").text}</h3>
                  <form onSubmit={handleRecordScan} className="mobile-stock-take-form">
                    <div className="mobile-stock-take-form-row">
                      <label htmlFor={barcodeInputId} className="inventory-form-field">
                        {t("inventory.stock_take.barcode_label").text}
                        <input
                          id={barcodeInputId}
                          type="text"
                          value={barcodeInput}
                          onChange={(e) => setBarcodeInput(e.target.value)}
                          placeholder={t("inventory.stock_take.barcode_placeholder").text}
                          required
                          autoFocus
                          className="modern-text-input"
                        />
                      </label>
                    </div>

                    <div className="mobile-stock-take-form-grid">
                      <label htmlFor={quantityInputId} className="inventory-form-field">
                        {t("inventory.stock_take.quantity_label").text}
                        <input
                          id={quantityInputId}
                          type="number"
                          step="0.001"
                          min="0.001"
                          value={quantityInput}
                          onChange={(e) => setQuantityInput(e.target.value)}
                          required
                          className="modern-text-input"
                        />
                      </label>

                      <label htmlFor={conditionInputId} className="inventory-form-field">
                        <span>{t("inventory.stock_take.condition_label").text}</span>
                        <CustomSelect
                          id={conditionInputId}
                          ariaLabel={t("inventory.stock_take.condition_label").text}
                          value={conditionInput}
                          onChange={(val) => setConditionInput(val as "GOOD" | "DAMAGED")}
                          options={[
                            { value: "GOOD", label: t("inventory.stock_take.condition_good").text },
                            { value: "DAMAGED", label: t("inventory.stock_take.condition_damaged").text }
                          ]}
                        />
                      </label>
                    </div>

                    <div className="mobile-stock-take-form-row">
                      <label htmlFor={notesInputId} className="inventory-form-field">
                        {t("inventory.stock_take.notes_label").text}
                        <input
                          id={notesInputId}
                          type="text"
                          value={notesInput}
                          onChange={(e) => setNotesInput(e.target.value)}
                          placeholder={t("inventory.stock_take.notes_placeholder").text}
                          className="modern-text-input"
                        />
                      </label>
                    </div>

                    <button
                      type="submit"
                      className="action-button action-button--primary mobile-stock-take-submit-btn"
                      disabled={recordScanMutation.isPending || !barcodeInput.trim()}
                    >
                      {recordScanMutation.isPending
                        ? t("inventory.stock_take.scanning_active").text
                        : t("inventory.stock_take.record_scan_btn").text}
                    </button>
                  </form>
                </section>
              )}

              {/* Recent Scans Table */}
              <section className="modern-table-card mobile-stock-take-scans-card">
                <h3>{t("inventory.stock_take.recent_scans").text}</h3>
                {scansQuery.isLoading ? (
                  <p>{t("inventory.loading").text}</p>
                ) : (scansQuery.data ?? []).length === 0 ? (
                  <p className="mobile-stock-take-empty-notice">
                    {t("inventory.stock_take.no_scans_yet").text}
                  </p>
                ) : (
                  <div className="modern-table-scroll">
                    <table className="modern-table">
                      <thead>
                        <tr>
                          <th>{t("inventory.column.expiry").text}</th>
                          <th>{t("inventory.stock_take.barcode_label").text}</th>
                          <th>{t("inventory.column.item").text}</th>
                          <th>{t("inventory.column.qty").text}</th>
                          <th>{t("inventory.column.condition").text}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(scansQuery.data ?? []).map((scan: StockTakeScanItemResponse) => (
                          <tr key={scan.scanId}>
                            <td>{formatInstant(scan.scannedAt)}</td>
                            <td><code>{scan.scannedCode || scan.skuCode}</code></td>
                            <td>{scan.skuNameEn || scan.skuCode}</td>
                            <td><strong>{scan.countedQty.toFixed(3)}</strong></td>
                            <td>
                              <span className={`mobile-stock-take-chip mobile-stock-take-chip--${scan.condition.toLowerCase()}`}>
                                {scan.condition}
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </>
          )}

          {/* TAB 2: Review Variances & Reconcile */}
          {activeTab === "review" && (
            <>
              <section className="modern-table-card mobile-stock-take-review-card">
                <h3>{t("inventory.stock_take.review_title").text}</h3>
                {reviewQuery.isLoading ? (
                  <p>{t("inventory.loading").text}</p>
                ) : !reviewQuery.data ? (
                  <p className="mobile-stock-take-empty-notice">
                    {t("inventory.error.generic").text}
                  </p>
                ) : (
                  <>
                    <div className="mobile-stock-take-kpi-grid">
                      <div className="mobile-stock-take-kpi">
                        <span className="mobile-stock-take-kpi__label">
                          {t("inventory.stock_take.matched_lots").text}
                        </span>
                        <span className="mobile-stock-take-kpi__value">
                          {reviewQuery.data.matchedLots}
                        </span>
                      </div>
                      <div className="mobile-stock-take-kpi">
                        <span className="mobile-stock-take-kpi__label">
                          {t("inventory.stock_take.discrepant_lots").text}
                        </span>
                        <span className="mobile-stock-take-kpi__value">
                          {reviewQuery.data.discrepantLots}
                        </span>
                      </div>
                      <div className="mobile-stock-take-kpi">
                        <span className="mobile-stock-take-kpi__label">
                          {t("inventory.stock_take.uncounted_lots").text}
                        </span>
                        <span className="mobile-stock-take-kpi__value">
                          {reviewQuery.data.uncountedLots}
                        </span>
                      </div>
                      <div className="mobile-stock-take-kpi">
                        <span className="mobile-stock-take-kpi__label">
                          {t("inventory.stock_take.total_variance_qty").text}
                        </span>
                        <span className="mobile-stock-take-kpi__value">
                          {reviewQuery.data.totalVarianceQty.toFixed(3)}
                        </span>
                      </div>
                      <div className="mobile-stock-take-kpi">
                        <span className="mobile-stock-take-kpi__label">
                          {t("inventory.stock_take.total_variance_value").text}
                        </span>
                        <span className="mobile-stock-take-kpi__value">
                          <MoneyDisplay amount={reviewQuery.data.totalVarianceValue} />
                        </span>
                      </div>
                    </div>

                    <div className="modern-table-scroll" style={{ marginTop: "var(--space-4)" }}>
                      <table className="modern-table">
                        <thead>
                          <tr>
                            <th>{t("inventory.column.item").text}</th>
                            <th>{t("inventory.column.condition").text}</th>
                            <th>{t("inventory.column.on_hand").text}</th>
                            <th>{t("inventory.column.qty").text}</th>
                            <th>{t("inventory.stock_take.total_variance_qty").text}</th>
                            <th>{t("inventory.stock_take.total_variance_value").text}</th>
                            <th>{t("inventory.stock_take.session_status").text}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {reviewQuery.data.lines.map((line: StockTakeReviewLine, idx: number) => (
                            <tr key={`${line.skuId}-${line.condition}-${idx}`}>
                              <td>
                                <div><strong>{line.skuCode}</strong></div>
                                <div>{line.skuNameEn}</div>
                              </td>
                              <td>{line.condition}</td>
                              <td>{line.expectedQty.toFixed(3)}</td>
                              <td>{line.countedQty.toFixed(3)}</td>
                              <td>{line.varianceQty.toFixed(3)}</td>
                              <td><MoneyDisplay amount={line.varianceValue} /></td>
                              <td>
                                <span className={`mobile-stock-take-chip mobile-stock-take-chip--${line.status.toLowerCase()}`}>
                                  {line.status}
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </>
                )}
              </section>

              {/* Reconcile & Submit Form */}
              {isCounting && canRecord && (
                <section className="modern-table-card mobile-stock-take-reconcile-card">
                  <h3>{t("inventory.stock_take.reconcile_section").text}</h3>
                  <div className="mobile-stock-take-form-grid">
                    <label htmlFor={uncountedHandlingId} className="inventory-form-field">
                      <span>{t("inventory.stock_take.uncounted_handling").text}</span>
                      <CustomSelect
                        id={uncountedHandlingId}
                        ariaLabel={t("inventory.stock_take.uncounted_handling").text}
                        value={uncountedHandling}
                        onChange={(val) => setUncountedHandling(val as "ZERO" | "SKIP")}
                        options={[
                          { value: "ZERO", label: t("inventory.stock_take.uncounted_zero").text },
                          { value: "SKIP", label: t("inventory.stock_take.uncounted_skip").text }
                        ]}
                      />
                    </label>

                    {uncountedHandling === "SKIP" && (
                      <label htmlFor={skipReasonId} className="inventory-form-field">
                        {t("inventory.stock_take.skip_reason").text}
                        <input
                          id={skipReasonId}
                          type="text"
                          value={skipReason}
                          onChange={(e) => setSkipReason(e.target.value)}
                          required
                          className="modern-text-input"
                        />
                      </label>
                    )}
                  </div>

                  <button
                    type="button"
                    className="action-button action-button--primary mobile-stock-take-submit-btn"
                    onClick={() => reconcileMutation.mutate()}
                    disabled={
                      reconcileMutation.isPending ||
                      (uncountedHandling === "SKIP" && !skipReason.trim())
                    }
                  >
                    {reconcileMutation.isPending
                      ? t("inventory.stock_take.reconciling").text
                      : t("inventory.stock_take.reconcile_btn").text}
                  </button>
                </section>
              )}
            </>
          )}
        </>
      )}
    </main>
  );
}
