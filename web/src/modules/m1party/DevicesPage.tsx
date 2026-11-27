import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import "./m1party.css";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import type { ChipState } from "../../shell/components/StateChip";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useDeviceApi } from "./deviceApi";
import type { Device, DeviceStatus, EnrolmentCode, SequenceGap } from "./deviceApi";
import { refusalOf } from "./adminView";
import { CustomSelect } from "./CustomSelect";

const DEVICE_STATUS_LOOK: Record<string, ChipState> = {
  ENROLLED: "draft",
  ACTIVE: "issued",
  SUSPENDED: "alert",
  RETIRED: "void"
};

const DEVICE_STATUSES: DeviceStatus[] = ["ENROLLED", "ACTIVE", "SUSPENDED", "RETIRED"];

const RESET_REASON_CODES = ["OUTBOX_LOST", "DATABASE_CORRUPTED", "HARDWARE_REPLACED", "ADMIN_OVERRIDE"];

export function DevicesPage() {
  const t = useT();
  const api = useDeviceApi();
  const canEnrol = useHasPermission("sys.device.enrol");
  const queryClient = useQueryClient();
  const idempotencyKey = useIdempotencyKey();

  const [status, setStatus] = useState<DeviceStatus | "">("");

  // Modal states
  const [issuedCode, setIssuedCode] = useState<EnrolmentCode | null>(null);
  const [syncStatusDevice, setSyncStatusDevice] = useState<Device | null>(null);
  const [resetDevice, setResetDevice] = useState<Device | null>(null);
  const [newStartSeq, setNewStartSeq] = useState<string>("");
  const [resetReasonCode, setResetReasonCode] = useState<string>("OUTBOX_LOST");
  const [resetReasonText, setResetReasonText] = useState<string>("");
  const [resetResult, setResetResult] = useState<SequenceGap | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        if (issuedCode) setIssuedCode(null);
        else if (syncStatusDevice) setSyncStatusDevice(null);
        else if (resetDevice) {
          setResetDevice(null);
          setResetResult(null);
        }
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [issuedCode, syncStatusDevice, resetDevice]);

  const devicesQuery = useQuery({
    queryKey: ["party", "devices", status],
    queryFn: () => api.listDevices({ status: status || undefined })
  });

  const syncStatusQuery = useQuery({
    queryKey: ["sync", "device-status", syncStatusDevice?.deviceId],
    queryFn: () => api.getDeviceSyncStatus(syncStatusDevice!.deviceId),
    enabled: !!syncStatusDevice
  });

  const issueCodeMutation = useMutation({
    mutationFn: (deviceId: string) => api.issueEnrolmentCode(deviceId, idempotencyKey.current()),
    onSuccess: (data) => {
      idempotencyKey.next();
      setIssuedCode(data);
      setActionError(null);
    },
    onError: (err) => {
      setActionError(err);
    }
  });

  const resetSeqMutation = useMutation({
    mutationFn: ({ deviceId, newSeq, code, text }: { deviceId: string; newSeq: number; code: string; text?: string }) =>
      api.resetDeviceSequence(
        deviceId,
        { new_start_seq: newSeq, reason_code: code, reason_text: text || null },
        idempotencyKey.current()
      ),
    onSuccess: (gap) => {
      idempotencyKey.next();
      setResetResult(gap);
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ["sync", "device-status", gap.device_id] });
    },
    onError: (err) => {
      setActionError(err);
    }
  });

  const devices = devicesQuery.data ?? [];

  const handleOpenReset = (device: Device, currentSeq: number = 0) => {
    setResetDevice(device);
    setNewStartSeq(String(Math.max(2, currentSeq + 1)));
    setResetReasonCode("OUTBOX_LOST");
    setResetReasonText("");
    setResetResult(null);
    setActionError(null);
  };

  return (
    <main className="shell-page">
      <PageHeader icon="terminal" title={t("party.devices.title").text} />

      <form role="search" onSubmit={(e) => e.preventDefault()} className="modern-filter-panel devices-filter-panel users-filter-panel">
        <div className="modern-field">
          <span className="modern-field__label party-filter-label">{t("party.device.status").text}</span>
          <CustomSelect
            value={status}
            onChange={(val) => setStatus(val)}
            ariaLabel={t("party.device.status").text}
            options={[
              { value: "", label: t("party.filter.status.any").text },
              ...DEVICE_STATUSES.map((val) => ({
                value: val,
                label: val
              }))
            ]}
          />
        </div>
      </form>

      {devicesQuery.isLoading && <p>{t("party.list.loading").text}</p>}
      {devicesQuery.isError && <p role="alert">{refusalOf(devicesQuery.error, t("party.error.generic").text).text}</p>}
      {actionError ? <p role="alert">{refusalOf(actionError, t("party.error.generic").text).text}</p> : null}

      {devicesQuery.isSuccess && devices.length === 0 && (
        <div className="party-empty-card">
          <p className="party-empty-text">{t("party.devices.empty").text}</p>
        </div>
      )}

      {devices.length > 0 && (
        <div className="modern-table-card">
          <div className="modern-table-scroll">
            <table className="modern-table">
              <thead>
                <tr>
                  <th scope="col">{t("party.device.serial").text}</th>
                  <th scope="col">{t("party.device.kind").text}</th>
                  <th scope="col">{t("party.device.status").text}</th>
                  <th scope="col">{t("party.device.position").text}</th>
                  <th scope="col">{t("party.device.last_seen").text}</th>
                  <th scope="col">{t("party.device.actions").text}</th>
                </tr>
              </thead>
              <tbody>
                {devices.map((device) => {
                  const devStatus = device.status ?? "ENROLLED";
                  const look = DEVICE_STATUS_LOOK[devStatus] ?? "draft";
                  const isEnrollable = devStatus === "ENROLLED" || devStatus === "ACTIVE";

                  return (
                    <tr key={device.deviceId}>
                      <td>
                        <strong>{device.hardwareSerial}</strong>
                      </td>
                      <td>{device.deviceKind}</td>
                      <td>
                        <StateChip state={look} label={devStatus} />
                      </td>
                      <td>{device.tillPositionId ?? t("party.device.sync.none").text}</td>
                      <td>{device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString() : t("party.device.sync.none").text}</td>
                      <td>
                        <div className="device-actions-row">
                          {canEnrol && (
                            <button
                              type="button"
                              className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                              disabled={!isEnrollable || issueCodeMutation.isPending}
                              onClick={() => issueCodeMutation.mutate(device.deviceId)}
                            >
                              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                                <circle cx="7.5" cy="15.5" r="4.5" />
                                <path d="M10.5 12.5L20 3m-3 0l3 3m-4.5 1.5l2 2" />
                              </svg>
                              <span>{t("party.device.action.enrol_code").text}</span>
                            </button>
                          )}
                          <button
                            type="button"
                            className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                            onClick={() => {
                              setActionError(null);
                              setSyncStatusDevice(device);
                            }}
                          >
                            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                              <polyline points="23 4 23 10 17 10" />
                              <polyline points="1 20 1 14 7 14" />
                              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
                            </svg>
                            <span>{t("party.device.action.sync_status").text}</span>
                          </button>
                          {canEnrol && (
                            <button
                              type="button"
                              className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                              onClick={() => handleOpenReset(device)}
                            >
                              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                                <polyline points="1 4 1 10 7 10" />
                                <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
                              </svg>
                              <span>{t("party.device.action.reset_seq").text}</span>
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Enrolment Code Modal */}
      {issuedCode && typeof document !== "undefined" && createPortal(
        <div
          className="device-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="enrol-modal-title"
          onClick={(e) => {
            if (e.target === e.currentTarget) setIssuedCode(null);
          }}
        >
          <div className="device-modal-card device-modal-card--enrol">
            <div className="device-modal-header">
              <div className="device-modal-header-left">
                <div className="device-modal-icon-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="7.5" cy="15.5" r="4.5" />
                    <path d="M10.5 12.5L20 3m-3 0l3 3m-4.5 1.5l2 2" />
                  </svg>
                </div>
                <div>
                  <h2 id="enrol-modal-title" className="device-modal-title">
                    {t("party.device.enrol.title").text}
                  </h2>
                  <p className="device-modal-subtitle">
                    {t("party.device.enrol.notice").text}
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="device-modal-close-btn"
                onClick={() => setIssuedCode(null)}
                aria-label={t("party.device.close_dialog").text}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>

            <div className="device-enrol-card">
              <div className="device-code-box" aria-label={t("party.device.enrol.code").text}>
                <span className="device-code-text">{issuedCode.code}</span>
                <button
                  type="button"
                  className="device-copy-btn"
                  onClick={() => {
                    navigator.clipboard.writeText(issuedCode.code);
                    setCopied(true);
                    setTimeout(() => setCopied(false), 2000);
                  }}
                  title={copied ? t("party.device.enrol.copied").text : t("party.device.enrol.copy").text}
                  aria-label={copied ? t("party.device.enrol.copied").text : t("party.device.enrol.copy").text}
                >
                  {copied ? (
                    <>
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                      <span>{t("party.device.enrol.copied").text}</span>
                    </>
                  ) : (
                    <>
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                      </svg>
                      <span>{t("party.device.enrol.copy").text}</span>
                    </>
                  )}
                </button>
              </div>

              <div className="device-enrol-expiry">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <circle cx="12" cy="12" r="10" />
                  <polyline points="12 6 12 12 16 14" />
                </svg>
                <span>
                  {t("party.device.enrol.expires").text}: <strong>{new Date(issuedCode.expires_at).toLocaleString()}</strong>
                </span>
              </div>
            </div>

            <div className="device-modal-actions">
              <button
                type="button"
                className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                onClick={() => setIssuedCode(null)}
              >
                {t("party.device.close").text}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Sync Status Modal */}
      {syncStatusDevice && typeof document !== "undefined" && createPortal(
        <div
          className="device-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="sync-modal-title"
          onClick={(e) => {
            if (e.target === e.currentTarget) setSyncStatusDevice(null);
          }}
        >
          <div className="device-modal-card device-modal-card--sync">
            <div className="device-modal-header">
              <div className="device-modal-header-left">
                <div className="device-modal-icon-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="23 4 23 10 17 10" />
                    <polyline points="1 20 1 14 7 14" />
                    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
                  </svg>
                </div>
                <div>
                  <h2 id="sync-modal-title" className="device-modal-title">
                    {t("party.device.sync.title").text}: {syncStatusDevice.hardwareSerial}
                  </h2>
                  <p className="device-modal-subtitle">
                    {syncStatusDevice.deviceKind} · {syncStatusDevice.status}
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="device-modal-close-btn"
                onClick={() => setSyncStatusDevice(null)}
                aria-label={t("party.device.close_dialog").text}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>

            {syncStatusQuery.isLoading && <p>{t("party.list.loading").text}</p>}
            {syncStatusQuery.isError && (
              <p role="alert">{refusalOf(syncStatusQuery.error, t("party.error.generic").text).text}</p>
            )}

            {syncStatusQuery.data && (
              <>
                <div className="device-kpi-grid device-facts-grid">
                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.last_applied_seq").text}</span>
                      <span className="device-kpi-val device-fact-value">{syncStatusQuery.data.last_applied_seq}</span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <line x1="4" y1="9" x2="20" y2="9" />
                        <line x1="4" y1="15" x2="20" y2="15" />
                        <line x1="10" y1="3" x2="8" y2="21" />
                        <line x1="16" y1="3" x2="14" y2="21" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.last_applied_at").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.last_applied_at
                          ? new Date(syncStatusQuery.data.last_applied_at).toLocaleString()
                          : t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="10" />
                        <polyline points="12 6 12 12 16 14" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.gap_rejections").text}</span>
                      <span className="device-kpi-val device-fact-value">{syncStatusQuery.data.gap_rejections}</span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                        <line x1="12" y1="9" x2="12" y2="13" />
                        <line x1="12" y1="17" x2="12.01" y2="17" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.in_flight").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.in_flight_batch_id ?? t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <line x1="22" y1="2" x2="11" y2="13" />
                        <polygon points="22 2 15 22 11 13 2 9 22 2" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.app_version").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.app_version ?? t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="4 17 10 11 4 5" />
                        <line x1="12" y1="19" x2="20" y2="19" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.battery").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.battery_pct !== null && syncStatusQuery.data.battery_pct !== undefined
                          ? `${syncStatusQuery.data.battery_pct}%`
                          : t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="1" y="6" width="18" height="12" rx="2" ry="2" />
                        <line x1="23" y1="10" x2="23" y2="14" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.storage").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.storage_free_mb !== null && syncStatusQuery.data.storage_free_mb !== undefined
                          ? `${syncStatusQuery.data.storage_free_mb} MB`
                          : t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <ellipse cx="12" cy="5" rx="9" ry="3" />
                        <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
                        <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.pending_events").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.pending_event_count ?? t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
                        <path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.clock_offset").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.clock_offset_ms !== null && syncStatusQuery.data.clock_offset_ms !== undefined
                          ? `${syncStatusQuery.data.clock_offset_ms} ms`
                          : t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="10" />
                        <polyline points="12 6 12 12 14 10" />
                      </svg>
                    </div>
                  </div>

                  <div className="device-kpi-card device-fact-item">
                    <div className="device-kpi-content">
                      <span className="device-kpi-label device-fact-label">{t("party.device.sync.session").text}</span>
                      <span className="device-kpi-val device-fact-value">
                        {syncStatusQuery.data.open_session === true
                          ? t("party.device.sync.yes").text
                          : syncStatusQuery.data.open_session === false
                          ? t("party.device.sync.no").text
                          : t("party.device.sync.none").text}
                      </span>
                    </div>
                    <div className="device-kpi-icon-badge" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                      </svg>
                    </div>
                  </div>
                </div>

                <div className="device-gaps-section">
                  <h3 className="device-modal-section-title">
                    {t("party.device.sync.gaps_title").text}
                  </h3>
                  {syncStatusQuery.data.recent_gaps && syncStatusQuery.data.recent_gaps.length > 0 ? (
                    <div className="modern-table-card">
                      <div className="modern-table-scroll">
                        <table className="modern-table">
                          <thead>
                            <tr>
                              <th>{t("party.device.sync.from_seq").text}</th>
                              <th>{t("party.device.sync.to_seq").text}</th>
                              <th>{t("party.device.sync.recorded_at").text}</th>
                            </tr>
                          </thead>
                          <tbody>
                            {syncStatusQuery.data.recent_gaps.map((g) => (
                              <tr key={g.gap_id}>
                                <td>{g.from_seq}</td>
                                <td>{g.to_seq}</td>
                                <td>{new Date(g.recorded_at).toLocaleString()}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  ) : (
                    <div className="device-gaps-empty-wrap">
                      <div className="device-gaps-empty-badge" aria-hidden="true">
                        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      </div>
                      <div>
                        <span className="device-gaps-empty-title">{t("party.device.sync.no_gaps").text}</span>
                        <p className="device-empty-text">{t("party.device.sync.no_gaps").text}</p>
                      </div>
                    </div>
                  )}
                </div>
              </>
            )}

            <div className="device-modal-actions device-modal-actions--between">
              {canEnrol ? (
                <button
                  type="button"
                  className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                  onClick={() => {
                    const curSeq = syncStatusQuery.data?.last_applied_seq ?? 0;
                    const dev = syncStatusDevice;
                    setSyncStatusDevice(null);
                    handleOpenReset(dev, curSeq);
                  }}
                >
                  <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <polyline points="1 4 1 10 7 10" />
                    <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
                  </svg>
                  <span>{t("party.device.action.reset_seq").text}</span>
                </button>
              ) : <div />}
              <button
                type="button"
                className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                onClick={() => setSyncStatusDevice(null)}
              >
                {t("party.device.close").text}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Sequence Reset Modal */}
      {resetDevice && typeof document !== "undefined" && createPortal(
        <div
          className="device-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="reset-modal-title"
          onClick={(e) => {
            if (e.target === e.currentTarget && !resetSeqMutation.isPending) {
              setResetDevice(null);
              setResetResult(null);
            }
          }}
        >
          <form
            className="device-modal-card device-modal-card--reset"
            onSubmit={(e) => {
              e.preventDefault();
              const seq = parseInt(newStartSeq, 10);
              if (isNaN(seq) || seq < 2) return;
              resetSeqMutation.mutate({
                deviceId: resetDevice.deviceId,
                newSeq: seq,
                code: resetReasonCode,
                text: resetReasonText
              });
            }}
          >
            <div className="device-modal-header">
              <div className="device-modal-header-left">
                <div className="device-modal-icon-badge" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="1 4 1 10 7 10" />
                    <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
                  </svg>
                </div>
                <div>
                  <h2 id="reset-modal-title" className="device-modal-title">
                    {t("party.device.reset.title").text}: {resetDevice.hardwareSerial}
                  </h2>
                  <p className="device-modal-subtitle">
                    {resetDevice.deviceKind} · {resetDevice.status}
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="device-modal-close-btn"
                onClick={() => {
                  setResetDevice(null);
                  setResetResult(null);
                }}
                disabled={resetSeqMutation.isPending}
                aria-label={t("party.device.close_dialog").text}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>

            {resetResult ? (
              <div className="device-enrol-card">
                <div className="device-gaps-empty-wrap">
                  <div className="device-gaps-empty-badge" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                  </div>
                  <div>
                    <strong className="device-gaps-empty-title">
                      {t("party.device.reset.success", undefined, { nextSeq: resetResult.to_seq + 1 }).text}
                    </strong>
                    <p className="device-gaps-empty-desc">{t("party.device.reset.updated").text}</p>
                  </div>
                </div>
                <div className="device-modal-actions">
                  <button
                    type="button"
                    className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                    onClick={() => {
                      setResetDevice(null);
                      setResetResult(null);
                    }}
                  >
                    {t("party.device.close").text}
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div className="device-info-banner">
                  <div className="device-info-banner-badge" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10" />
                      <line x1="12" y1="16" x2="12" y2="12" />
                      <line x1="12" y1="8" x2="12.01" y2="8" />
                    </svg>
                  </div>
                  <div className="device-info-banner-text">
                    <span className="device-reset-cursor-info">
                      {t("party.device.reset.cursor_info", undefined, { currentSeq: parseInt(newStartSeq, 10) - 1 || 0 }).text}
                    </span>
                  </div>
                </div>

                {actionError ? <p role="alert" className="device-modal-alert">{refusalOf(actionError, t("party.error.generic").text).text}</p> : null}

                <div className="device-reset-fields">
                  <label className="modern-field device-reset-field">
                    <span className="modern-field__label">
                      {t("party.device.reset.new_seq").text}
                    </span>
                    <input
                      type="number"
                      min={2}
                      value={newStartSeq}
                      onChange={(e) => setNewStartSeq(e.target.value)}
                      required
                      className="modern-input device-reset-input"
                    />
                  </label>

                  <div className="modern-field device-reset-field">
                    <span className="modern-field__label">
                      {t("party.device.reset.reason_code").text}
                    </span>
                    <CustomSelect
                      id="reset-reason-code"
                      value={resetReasonCode}
                      onChange={(val) => setResetReasonCode(val)}
                      ariaLabel={t("party.device.reset.reason_code").text}
                      options={RESET_REASON_CODES.map((code) => ({
                        value: code,
                        label: code
                      }))}
                    />
                  </div>

                  <label className="modern-field device-reset-field">
                    <span className="modern-field__label">{t("party.device.reset.reason_text").text}</span>
                    <textarea
                      value={resetReasonText}
                      onChange={(e) => setResetReasonText(e.target.value)}
                      maxLength={500}
                      rows={3}
                      placeholder={t("party.device.reset.reason_text").text}
                      className="modern-textarea device-reset-textarea"
                    />
                  </label>
                </div>

                <div className="device-modal-actions">
                  <button
                    type="button"
                    className="device-action-btn catalogue-action-btn catalogue-action-btn--secondary device-table-btn"
                    disabled={resetSeqMutation.isPending}
                    onClick={() => setResetDevice(null)}
                  >
                    {t("party.device.close").text}
                  </button>
                  <button
                    type="submit"
                    className="device-action-btn catalogue-action-btn catalogue-action-btn--primary device-submit-btn"
                    disabled={resetSeqMutation.isPending}
                  >
                    {resetSeqMutation.isPending
                      ? t("party.device.reset.submitting").text
                      : t("party.device.reset.submit").text}
                  </button>
                </div>
              </>
            )}
          </form>
        </div>,
        document.body
      )}
    </main>
  );
}
