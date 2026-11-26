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

      <form role="search" onSubmit={(e) => e.preventDefault()} className="modern-filter-panel">
        <label className="modern-field">
          <span className="modern-field__label">{t("party.device.status").text}</span>
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
        </label>
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
                              className="device-action-btn device-action-btn--primary"
                              disabled={!isEnrollable || issueCodeMutation.isPending}
                              onClick={() => issueCodeMutation.mutate(device.deviceId)}
                            >
                              {t("party.device.action.enrol_code").text}
                            </button>
                          )}
                          <button
                            type="button"
                            className="device-action-btn"
                            onClick={() => {
                              setActionError(null);
                              setSyncStatusDevice(device);
                            }}
                          >
                            {t("party.device.action.sync_status").text}
                          </button>
                          {canEnrol && (
                            <button
                              type="button"
                              className="device-action-btn"
                              onClick={() => handleOpenReset(device)}
                            >
                              {t("party.device.action.reset_seq").text}
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
      {issuedCode && (
        <div className="device-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="enrol-modal-title">
          <div className="device-modal-card">
            <h2 id="enrol-modal-title" className="device-modal-title">
              {t("party.device.enrol.title").text}
            </h2>
            <p>{t("party.device.enrol.notice").text}</p>
            <div className="device-code-box" aria-label={t("party.device.enrol.code").text}>
              {issuedCode.code}
            </div>
            <p>
              {t("party.device.enrol.expires").text}: {new Date(issuedCode.expires_at).toLocaleString()}
            </p>
            <div style={{ display: "flex", justifyContent: "flex-end" }}>
              <button
                type="button"
                className="device-action-btn device-action-btn--primary"
                onClick={() => setIssuedCode(null)}
              >
                {t("party.device.close").text}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Sync Status Modal */}
      {syncStatusDevice && (
        <div className="device-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="sync-modal-title">
          <div className="device-modal-card">
            <h2 id="sync-modal-title" className="device-modal-title">
              {t("party.device.sync.title").text}: {syncStatusDevice.hardwareSerial}
            </h2>

            {syncStatusQuery.isLoading && <p>{t("party.list.loading").text}</p>}
            {syncStatusQuery.isError && (
              <p role="alert">{refusalOf(syncStatusQuery.error, t("party.error.generic").text).text}</p>
            )}

            {syncStatusQuery.data && (
              <>
                <div className="device-facts-grid">
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.last_applied_seq").text}</span>
                    <span className="device-fact-value">{syncStatusQuery.data.last_applied_seq}</span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.last_applied_at").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.last_applied_at
                        ? new Date(syncStatusQuery.data.last_applied_at).toLocaleString()
                        : t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.gap_rejections").text}</span>
                    <span className="device-fact-value">{syncStatusQuery.data.gap_rejections}</span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.in_flight").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.in_flight_batch_id ?? t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.app_version").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.app_version ?? t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.battery").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.battery_pct !== null && syncStatusQuery.data.battery_pct !== undefined
                        ? `${syncStatusQuery.data.battery_pct}%`
                        : t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.storage").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.storage_free_mb !== null && syncStatusQuery.data.storage_free_mb !== undefined
                        ? `${syncStatusQuery.data.storage_free_mb} MB`
                        : t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.pending_events").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.pending_event_count ?? t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.clock_offset").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.clock_offset_ms !== null && syncStatusQuery.data.clock_offset_ms !== undefined
                        ? `${syncStatusQuery.data.clock_offset_ms} ms`
                        : t("party.device.sync.none").text}
                    </span>
                  </div>
                  <div className="device-fact-item">
                    <span className="device-fact-label">{t("party.device.sync.session").text}</span>
                    <span className="device-fact-value">
                      {syncStatusQuery.data.open_session === true
                        ? t("party.device.sync.yes").text
                        : syncStatusQuery.data.open_session === false
                        ? t("party.device.sync.no").text
                        : t("party.device.sync.none").text}
                    </span>
                  </div>
                </div>

                <h3 style={{ marginTop: "var(--space-2)", marginBottom: "var(--space-1)", fontSize: "var(--font-size-md)" }}>
                  {t("party.device.sync.gaps_title").text}
                </h3>
                {syncStatusQuery.data.recent_gaps && syncStatusQuery.data.recent_gaps.length > 0 ? (
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
                ) : (
                  <p>{t("party.device.sync.no_gaps").text}</p>
                )}
              </>
            )}

            <div style={{ display: "flex", justifyContent: "space-between", marginTop: "var(--space-2)" }}>
              {canEnrol && (
                <button
                  type="button"
                  className="device-action-btn"
                  onClick={() => {
                    const curSeq = syncStatusQuery.data?.last_applied_seq ?? 0;
                    const dev = syncStatusDevice;
                    setSyncStatusDevice(null);
                    handleOpenReset(dev, curSeq);
                  }}
                >
                  {t("party.device.action.reset_seq").text}
                </button>
              )}
              <button
                type="button"
                className="device-action-btn device-action-btn--primary"
                onClick={() => setSyncStatusDevice(null)}
              >
                {t("party.device.close").text}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Sequence Reset Modal */}
      {resetDevice && (
        <div className="device-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="reset-modal-title">
          <form
            className="device-modal-card"
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
            <h2 id="reset-modal-title" className="device-modal-title">
              {t("party.device.reset.title").text}: {resetDevice.hardwareSerial}
            </h2>

            {resetResult ? (
              <div>
                <p role="status">
                  {t("party.device.reset.success", undefined, { nextSeq: resetResult.to_seq + 1 }).text}
                </p>
                <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "var(--space-2)" }}>
                  <button
                    type="button"
                    className="device-action-btn device-action-btn--primary"
                    onClick={() => setResetDevice(null)}
                  >
                    {t("party.device.close").text}
                  </button>
                </div>
              </div>
            ) : (
              <>
                <p>
                  {t("party.device.reset.cursor_info", undefined, { currentSeq: parseInt(newStartSeq, 10) - 1 || 0 }).text}
                </p>

                {actionError ? <p role="alert">{refusalOf(actionError, t("party.error.generic").text).text}</p> : null}

                <label className="modern-field">
                  <span className="modern-field__label">{t("party.device.reset.new_seq").text}</span>
                  <input
                    type="number"
                    min={2}
                    value={newStartSeq}
                    onChange={(e) => setNewStartSeq(e.target.value)}
                    required
                    className="modern-input"
                  />
                </label>

                <label className="modern-field">
                  <span className="modern-field__label">{t("party.device.reset.reason_code").text}</span>
                  <select
                    value={resetReasonCode}
                    onChange={(e) => setResetReasonCode(e.target.value)}
                    required
                    className="modern-input"
                  >
                    {RESET_REASON_CODES.map((code) => (
                      <option key={code} value={code}>
                        {code}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="modern-field">
                  <span className="modern-field__label">{t("party.device.reset.reason_text").text}</span>
                  <textarea
                    value={resetReasonText}
                    onChange={(e) => setResetReasonText(e.target.value)}
                    maxLength={500}
                    className="modern-textarea"
                  />
                </label>

                <div style={{ display: "flex", justifyContent: "flex-end", gap: "var(--space-1)" }}>
                  <button
                    type="button"
                    className="device-action-btn"
                    disabled={resetSeqMutation.isPending}
                    onClick={() => setResetDevice(null)}
                  >
                    {t("party.device.close").text}
                  </button>
                  <button
                    type="submit"
                    className="device-action-btn device-action-btn--primary"
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
        </div>
      )}
    </main>
  );
}
