import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate, useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi } from "./inventoryApi";
import { LocationPicker } from "./LocationPicker";
import { CustomSelect } from "./CustomSelect";
import { SkuLabel } from "./SkuLabel";
import { countChip } from "./stockControl";
import { errorText } from "./stockView";
import { PageHeader } from "../../shell/components/PageHeader";
import "./inventory.css";

/**
 * Stock counts at a location (25A section 8, "Stocktake sheet", back office; doc 25 flow 6.3): the
 * counts there with their state, a form that schedules and starts a count of everything or of some
 * items, and the lots below zero that the tills oversold (doc 25 flow 6.2), to acknowledge until a
 * count corrects them. The count itself is on its own page.
 */
export function CountsPage() {
  const t = useT();
  const api = useInventoryApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const canSchedule = useHasPermission("inv.count.schedule");
  const canRecord = useHasPermission("shop.count.record");
  const scheduleKey = useIdempotencyKey();
  const startKey = useIdempotencyKey();
  const acknowledgeKey = useIdempotencyKey();
  const [locationId, setLocationId] = useState("");
  const [scopeKind, setScopeKind] = useState<"FULL" | "SKUS">("SKUS");
  const [picked, setPicked] = useState<string[]>([]);
  const [acknowledging, setAcknowledging] = useState<string | null>(null);

  const counts = useQuery({
    queryKey: ["inventory", "counts", locationId],
    queryFn: () => api.counts(locationId),
    enabled: locationId !== ""
  });
  const lots = useQuery({
    queryKey: ["inventory", "balances", locationId],
    queryFn: () => api.balances(locationId),
    enabled: locationId !== ""
  });
  const negative = useQuery({
    queryKey: ["inventory", "negative-lots", locationId],
    queryFn: () => api.negativeLots(locationId),
    enabled: locationId !== ""
  });
  const skuIds = [...new Set((lots.data ?? []).map((lot) => lot.skuId))];

  const start = useMutation({
    mutationFn: async () => {
      const scheduled = await api.scheduleCount(
        { locationId, scopeKind, skuIds: scopeKind === "SKUS" ? picked : [] },
        scheduleKey.current()
      );
      scheduleKey.next();
      await api.startCount(scheduled.taskId, startKey.current());
      startKey.next();
      return scheduled.taskId;
    },
    onSuccess: (taskId) => {
      queryClient.invalidateQueries({ queryKey: ["inventory"] });
      navigate(`/inventory/counts/${taskId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        scheduleKey.next();
        startKey.next();
      }
    }
  });
  const acknowledge = useMutation({
    mutationFn: ({ stockLotId, reason }: { stockLotId: string; reason: string }) =>
      api.acknowledgeNegativeLot(stockLotId, reason, acknowledgeKey.current()),
    onSuccess: () => {
      acknowledgeKey.next();
      setAcknowledging(null);
      queryClient.invalidateQueries({ queryKey: ["inventory", "negative-lots"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        acknowledgeKey.next();
      }
    }
  });

  const toggle = (skuId: string) =>
    setPicked(picked.includes(skuId) ? picked.filter((id) => id !== skuId) : [...picked, skuId]);
  const ready = locationId !== "" && (scopeKind === "FULL" || picked.length > 0);

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
      <PageHeader
        icon="counts"
        title={t("inventory.count.title").text}
      />

      <div className="inventory-control-links">
        <Link className="action-link action-link--primary" to="/inventory/counts">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M9 11l3 3L22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </svg>
          <span>{t("inventory.counts.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/write-offs">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="9" y1="15" x2="15" y2="15" />
          </svg>
          <span>{t("inventory.writeoffs.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/repacks">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <path d="m21 16-4 4-4-4" />
            <path d="M17 20V4" />
            <path d="m3 8 4-4 4 4" />
            <path d="M7 4v16" />
          </svg>
          <span>{t("inventory.repacks.link").text}</span>
        </Link>
        <Link className="action-link" to="/inventory/transfer-requests">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <rect x="1" y="3" width="15" height="13" />
            <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
            <circle cx="5.5" cy="18.5" r="2.5" />
            <circle cx="18.5" cy="18.5" r="2.5" />
          </svg>
          <span>{t("inventory.request.link").text}</span>
        </Link>
      </div>

      <section className="modern-filter-panel modern-filter-panel--stock">
        <div className="modern-location-picker">
          <LocationPicker value={locationId} onChange={setLocationId} />
        </div>
      </section>

      {canSchedule && canRecord && locationId !== "" && (
        <section className="modern-table-card count-start-card">
          <div className="count-start-card__header">
            <span className="count-start-card__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M9 11l3 3L22 4" />
                <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
              </svg>
            </span>
            <h2 className="count-start-title">{t("inventory.count.start_section").text}</h2>
          </div>
          <div className="count-form-field">
            <label className="count-field-label" htmlFor="count-scope-select">
              <span>{t("inventory.count.scope").text}</span>
            </label>
            <div className="count-select-wrapper">
              <CustomSelect
                id="count-scope-select"
                ariaLabel={t("inventory.count.scope").text}
                value={scopeKind}
                onChange={(val) => setScopeKind(val as "FULL" | "SKUS")}
                options={[
                  { value: "SKUS", label: t("inventory.count.scope.SKUS").text },
                  { value: "FULL", label: t("inventory.count.scope.FULL").text }
                ]}
              />
            </div>
          </div>
          {scopeKind === "SKUS" && (
            <div className="count-items-group" role="group" aria-labelledby="pick-items-legend">
              <span id="pick-items-legend" className="count-field-label">
                {t("inventory.count.pick_items").text}
              </span>
              <div className="count-items-container">
                {skuIds.length === 0 && <p className="count-items-empty">{t("inventory.balances.empty").text}</p>}
                {skuIds.map((skuId) => (
                  <label key={skuId} className={`count-item-row ${picked.includes(skuId) ? "count-item-row--selected" : ""}`}>
                    <input
                      type="checkbox"
                      className="count-item-checkbox"
                      checked={picked.includes(skuId)}
                      onChange={() => toggle(skuId)}
                    />
                    <span className="count-item-label">
                      <SkuLabel skuId={skuId} />
                    </span>
                  </label>
                ))}
              </div>
            </div>
          )}
          <div className="count-action-bar">
            <button
              type="button"
              className="modern-btn modern-btn--primary count-start-btn"
              disabled={!ready || start.isPending}
              onClick={() => start.mutate()}
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                <polygon points="5 3 19 12 5 21 5 3" fill="currentColor" />
              </svg>
              <span>{t("inventory.count.start").text}</span>
            </button>
          </div>
          {start.isError && <p role="alert">{errorText(start.error, t("inventory.error.generic").text)}</p>}
        </section>
      )}

      <section className="modern-table-card">
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
              <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
              <polyline points="9 14 11 16 15 12" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.count.list").text}</h2>
        </div>
        {counts.isError && <p role="alert" style={{ padding: 'var(--space-3) var(--space-4)' }}>{errorText(counts.error, t("inventory.error.generic").text)}</p>}
        {counts.data?.length === 0 && <p style={{ padding: 'var(--space-3) var(--space-4)' }}>{t("inventory.count.none").text}</p>}
        {counts.data && counts.data.length > 0 && (
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.count.scheduled_for").text}</th>
                  <th>{t("inventory.count.scope").text}</th>
                  <th>{t("inventory.repack.column.status").text}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {counts.data.map((count) => (
                  <tr key={count.taskId}>
                    <td>{formatDate(count.scheduledFor)}</td>
                    <td>{t(`inventory.count.scope.${count.scopeKind}`).text}</td>
                    <td>
                      <StateChip state={countChip(count.status)} label={t(`inventory.count.status.${count.status}`).text} />
                      {count.outcome && ` ${t(`inventory.count.outcome.${count.outcome}`).text}`}
                    </td>
                    <td>
                      <Link className="table-action-btn" to={`/inventory/counts/${count.taskId}`}>{t("inventory.count.open").text}</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="modern-table-card" style={{ marginTop: 'var(--space-4)' }}>
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <circle cx="12" cy="12" r="10" />
              <line x1="8" y1="12" x2="16" y2="12" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.negative.title").text}</h2>
        </div>
        {negative.data?.length === 0 && <p style={{ padding: 'var(--space-3) var(--space-4)' }}>{t("inventory.negative.none").text}</p>}
        {negative.data && negative.data.length > 0 && (
          <div className="modern-table-scroll">
            <table className="modern-table stock-table">
              <thead>
                <tr>
                  <th>{t("inventory.column.item").text}</th>
                  <th>{t("inventory.column.batch").text}</th>
                  <th>{t("inventory.column.on_hand").text}</th>
                  <th>{t("inventory.negative.since").text}</th>
                  <th>{t("inventory.negative.acknowledged").text}</th>
                </tr>
              </thead>
              <tbody>
                {negative.data.map((lot) => (
                  <tr key={lot.stockLotId}>
                    <td>
                      <SkuLabel skuId={lot.skuId} />
                    </td>
                    <td>{lot.batchNo ?? ""}</td>
                    <td className="numeric-cell">{lot.qtyOnHand}</td>
                    <td>{lot.negativeSince ? formatInstant(lot.negativeSince) : ""}</td>
                    <td>
                      {lot.acknowledgedAt ? (
                        formatInstant(lot.acknowledgedAt)
                      ) : canSchedule ? (
                        <button type="button" className="table-action-btn" onClick={() => setAcknowledging(lot.stockLotId)}>
                          {t("inventory.negative.acknowledge").text}
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {acknowledging && (
        <ReasonCapture
          title={t("inventory.negative.question").text}
          codes={["OVERSOLD_OFFLINE", "RECEIPT_NOT_RECORDED", "OTHER"].map((code) => ({
            code,
            label: t(`inventory.negative.reason.${code}`).text
          }))}
          pending={acknowledge.isPending}
          onCancel={() => setAcknowledging(null)}
          onConfirm={(code, text) =>
            acknowledge.mutate({
              stockLotId: acknowledging,
              reason: [t(`inventory.negative.reason.${code}`).text, text].filter(Boolean).join(": ")
            })
          }
        />
      )}
      {acknowledge.isError && <p role="alert">{errorText(acknowledge.error, t("inventory.error.generic").text)}</p>}
    </main>
  );
}
