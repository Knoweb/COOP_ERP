import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import type { ChipState } from "../../shell/components/StateChip";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useDeviceApi } from "./deviceApi";
import type { QuarantinedEvent } from "./deviceApi";
import { refusalOf } from "./adminView";

const QUARANTINE_REASON_LOOK: Record<string, ChipState> = {
  SCHEMA: "alert",
  DUPLICATE_ID: "void",
  HASH: "alert",
  TOO_LARGE: "void",
  FORBIDDEN_FIELD: "alert"
};

const RESOLUTION_REASON_CODES = [
  "RESENT_CORRECTED",
  "DUPLICATE_OF_APPLIED",
  "TEST_EVENT",
  "DATA_CORRUPTED",
  "SCHEMA_MISMATCH"
];

export function QuarantinePage() {
  const t = useT();
  const api = useDeviceApi();
  const canResolve = useHasPermission("sync.quarantine.resolve");
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();

  const [filterMode, setFilterMode] = useState<"all" | "pending" | "resolved">("all");
  const [deviceIdFilter, setDeviceIdFilter] = useState<string>("");

  const [selectedEvent, setSelectedEvent] = useState<QuarantinedEvent | null>(null);
  const [resolutionType, setResolutionType] = useState<"REPAIRED" | "DISCARDED">("REPAIRED");
  const [reasonCode, setReasonCode] = useState<string>("RESENT_CORRECTED");
  const [reasonText, setReasonText] = useState<string>("");
  const [actionError, setActionError] = useState<unknown>(null);
  const [resolutionSuccess, setResolutionSuccess] = useState<string | null>(null);

  const resolvedParam = filterMode === "pending" ? false : filterMode === "resolved" ? true : undefined;

  const quarantineQuery = useQuery({
    queryKey: ["sync", "quarantine", filterMode, deviceIdFilter],
    queryFn: () =>
      api.listQuarantine({
        device_id: deviceIdFilter.trim() || undefined,
        resolved: resolvedParam,
        limit: 100
      })
  });

  const resolveMutation = useMutation({
    mutationFn: ({
      quarantineId,
      resolution,
      code,
      text
    }: {
      quarantineId: string;
      resolution: "REPAIRED" | "DISCARDED";
      code: string;
      text?: string;
    }) =>
      api.resolveQuarantine(
        quarantineId,
        {
          resolution,
          reason_code: code,
          reason_text: text || null
        },
        idempotencyKey.current()
      ),
    onSuccess: (res) => {
      idempotencyKey.next();
      setResolutionSuccess(res.resolution);
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ["sync", "quarantine"] });
    },
    onError: (err) => {
      setActionError(err);
    }
  });

  const events = quarantineQuery.data ?? [];

  const handleOpenInspect = (event: QuarantinedEvent) => {
    setSelectedEvent(event);
    setResolutionType("REPAIRED");
    setReasonCode("RESENT_CORRECTED");
    setReasonText("");
    setActionError(null);
    setResolutionSuccess(null);
  };

  const formatRawPayload = (raw: string | null | undefined): string => {
    if (!raw) return "";
    try {
      const parsed = JSON.parse(raw);
      return JSON.stringify(parsed, null, 2);
    } catch {
      return raw;
    }
  };

  return (
    <main className="shell-page">
      <PageHeader icon="shield" title={t("party.quarantine.title").text} />

      <form role="search" onSubmit={(e) => e.preventDefault()} className="modern-filter-panel">
        <label className="modern-field">
          <span className="modern-field__label">{t("party.quarantine.col.status").text}</span>
          <select
            value={filterMode}
            onChange={(e) => setFilterMode(e.target.value as "all" | "pending" | "resolved")}
            className="modern-input"
          >
            <option value="all">{t("party.quarantine.filter.all").text}</option>
            <option value="pending">{t("party.quarantine.filter.pending").text}</option>
            <option value="resolved">{t("party.quarantine.filter.resolved").text}</option>
          </select>
        </label>

        <label className="modern-field">
          <span className="modern-field__label">{t("party.quarantine.filter.device").text}</span>
          <input
            type="text"
            placeholder={t("party.quarantine.filter.device").text}
            value={deviceIdFilter}
            onChange={(e) => setDeviceIdFilter(e.target.value)}
            className="modern-input"
          />
        </label>
      </form>

      {quarantineQuery.isLoading && <p>{t("party.list.loading").text}</p>}
      {quarantineQuery.isError && (
        <p role="alert">{refusalOf(quarantineQuery.error, t("party.error.generic").text).text}</p>
      )}

      {quarantineQuery.isSuccess && events.length === 0 && (
        <div className="party-empty-card">
          <p className="party-empty-text">{t("party.quarantine.empty").text}</p>
        </div>
      )}

      {events.length > 0 && (
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.quarantine.col.received").text}</th>
                  <th scope="col">{t("party.quarantine.col.device").text}</th>
                  <th scope="col">{t("party.quarantine.col.seq").text}</th>
                  <th scope="col">{t("party.quarantine.col.reason").text}</th>
                  <th scope="col">{t("party.quarantine.col.event_type").text}</th>
                  <th scope="col">{t("party.quarantine.col.status").text}</th>
                  <th scope="col">{t("party.quarantine.col.actions").text}</th>
                </tr>
              </thead>
              <tbody>
                {events.map((ev) => {
                  const isResolved = ev.resolved_at !== null && ev.resolved_at !== undefined;
                  const statusLabel = isResolved
                    ? ev.resolution === "REPAIRED"
                      ? t("party.quarantine.status.repaired").text
                      : t("party.quarantine.status.discarded").text
                    : t("party.quarantine.status.pending").text;
                  const statusChipLook: ChipState = isResolved
                    ? ev.resolution === "REPAIRED"
                      ? "issued"
                      : "void"
                    : "alert";

                  const reasonLook = QUARANTINE_REASON_LOOK[ev.reason] ?? "alert";

                  return (
                    <tr key={ev.quarantine_id}>
                      <td>{new Date(ev.received_at).toLocaleString()}</td>
                      <td>
                        <code>{ev.device_id.slice(0, 8)}…</code>
                      </td>
                      <td>{ev.device_seq}</td>
                      <td>
                        <StateChip state={reasonLook} label={ev.reason} />
                      </td>
                      <td>{ev.event_type ?? t("party.device.sync.none").text}</td>
                      <td>
                        <StateChip state={statusChipLook} label={statusLabel} />
                      </td>
                      <td>
                        <button
                          type="button"
                          className="device-action-btn device-action-btn--primary"
                          onClick={() => handleOpenInspect(ev)}
                        >
                          {t("party.quarantine.action.inspect").text}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Detail & Resolution Modal */}
      {selectedEvent && (
        <div className="device-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="quarantine-modal-title">
          <div className="device-modal-card">
            <h2 id="quarantine-modal-title" className="device-modal-title">
              {t("party.quarantine.modal.title").text}
            </h2>

            <div className="device-facts-grid">
              <div className="device-fact-item">
                <span className="device-fact-label">{t("party.quarantine.modal.id").text}</span>
                <span className="device-fact-value">{selectedEvent.quarantine_id}</span>
              </div>
              <div className="device-fact-item">
                <span className="device-fact-label">{t("party.quarantine.col.device").text}</span>
                <span className="device-fact-value">{selectedEvent.device_id}</span>
              </div>
              <div className="device-fact-item">
                <span className="device-fact-label">{t("party.quarantine.col.seq").text}</span>
                <span className="device-fact-value">{selectedEvent.device_seq}</span>
              </div>
              <div className="device-fact-item">
                <span className="device-fact-label">{t("party.quarantine.modal.batch").text}</span>
                <span className="device-fact-value">{selectedEvent.batch_id}</span>
              </div>
              <div className="device-fact-item">
                <span className="device-fact-label">{t("party.quarantine.col.event_type").text}</span>
                <span className="device-fact-value">{selectedEvent.event_type ?? t("party.device.sync.none").text}</span>
              </div>
              <div className="device-fact-item">
                <span className="device-fact-label">{t("party.quarantine.col.reason").text}</span>
                <span className="device-fact-value">{selectedEvent.reason}</span>
              </div>
              {selectedEvent.detail && (
                <div className="device-fact-item" style={{ gridColumn: "1 / -1" }}>
                  <span className="device-fact-label">{t("party.quarantine.modal.detail").text}</span>
                  <span className="device-fact-value">{selectedEvent.detail}</span>
                </div>
              )}
            </div>

            <h3 style={{ marginTop: "var(--space-2)", marginBottom: "var(--space-1)", fontSize: "var(--font-size-md)" }}>
              {t("party.quarantine.modal.raw_payload").text}
            </h3>
            {selectedEvent.raw_event ? (
              <pre className="quarantine-pre">
                <code>{formatRawPayload(selectedEvent.raw_event)}</code>
              </pre>
            ) : (
              <p style={{ color: "var(--color-text-muted)" }}>{t("party.quarantine.modal.raw_purged").text}</p>
            )}

            {/* Resolution Information if Resolved */}
            {selectedEvent.resolved_at ? (
              <div style={{ marginTop: "var(--space-2)" }}>
                <h3 style={{ marginBottom: "var(--space-1)", fontSize: "var(--font-size-md)" }}>
                  {t("party.quarantine.modal.resolution_info").text}
                </h3>
                <div className="device-facts-grid">
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.quarantine.col.status").text}</span>
                    <span className="device-fact-value">{selectedEvent.resolution}</span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.quarantine.modal.resolved_at").text}</span>
                    <span className="device-fact-value">
                      {new Date(selectedEvent.resolved_at).toLocaleString()}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.quarantine.modal.resolved_by").text}</span>
                    <span className="device-fact-value">
                      {selectedEvent.resolved_by_user_id ?? t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.quarantine.modal.resolution_reason").text}</span>
                    <span className="device-fact-value">
                      {selectedEvent.resolution_reason ?? t("party.device.sync.none").text}
                    </span>
                  </div>
                </div>
              </div>
            ) : resolutionSuccess ? (
              <p role="status">
                {t("party.quarantine.resolve.success", undefined, { resolution: resolutionSuccess }).text}
              </p>
            ) : canResolve ? (
              /* Resolution Form */
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  resolveMutation.mutate({
                    quarantineId: selectedEvent.quarantine_id,
                    resolution: resolutionType,
                    code: reasonCode,
                    text: reasonText
                  });
                }}
                style={{ marginTop: "var(--space-2)" }}
              >
                <h3 style={{ marginBottom: "var(--space-1)", fontSize: "var(--font-size-md)" }}>
                  {t("party.quarantine.resolve.title").text}
                </h3>

                {actionError ? <p role="alert">{refusalOf(actionError, t("party.error.generic").text).text}</p> : null}

                <div style={{ display: "flex", gap: "var(--space-3)", marginBottom: "var(--space-2)" }}>
                  <label style={{ display: "flex", alignItems: "center", gap: "var(--space-half)" }}>
                    <input
                      type="radio"
                      name="resolution"
                      value="REPAIRED"
                      checked={resolutionType === "REPAIRED"}
                      onChange={() => setResolutionType("REPAIRED")}
                    />
                    <span>{t("party.quarantine.resolve.repaired").text}</span>
                  </label>
                  <label style={{ display: "flex", alignItems: "center", gap: "var(--space-half)" }}>
                    <input
                      type="radio"
                      name="resolution"
                      value="DISCARDED"
                      checked={resolutionType === "DISCARDED"}
                      onChange={() => setResolutionType("DISCARDED")}
                    />
                    <span>{t("party.quarantine.resolve.discarded").text}</span>
                  </label>
                </div>

                <label className="modern-field">
                  <span className="modern-field__label">{t("party.quarantine.resolve.reason_code").text}</span>
                  <select
                    value={reasonCode}
                    onChange={(e) => setReasonCode(e.target.value)}
                    required
                    className="modern-input"
                  >
                    {RESOLUTION_REASON_CODES.map((code) => (
                      <option key={code} value={code}>
                        {code}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="modern-field">
                  <span className="modern-field__label">{t("party.quarantine.resolve.reason_text").text}</span>
                  <input
                    type="text"
                    value={reasonText}
                    onChange={(e) => setReasonText(e.target.value)}
                    maxLength={500}
                    className="modern-input"
                  />
                </label>

                <div style={{ display: "flex", justifyContent: "flex-end", gap: "var(--space-1)", marginTop: "var(--space-2)" }}>
                  <button
                    type="submit"
                    className="device-action-btn device-action-btn--primary"
                    disabled={resolveMutation.isPending}
                  >
                    {resolveMutation.isPending
                      ? t("party.quarantine.resolve.submitting").text
                      : t("party.quarantine.resolve.submit").text}
                  </button>
                </div>
              </form>
            ) : null}

            <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "var(--space-2)" }}>
              <button
                type="button"
                className="device-action-btn"
                onClick={() => setSelectedEvent(null)}
              >
                {t("party.quarantine.close").text}
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
