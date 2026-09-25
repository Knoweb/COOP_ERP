package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The heartbeat of doc 32 section 6 (19A section 8, HeartbeatService): every five minutes when
 * online, on application start and after every batch. Records the fleet-health fields in the
 * kernel's own table ({@code kernel.device_heartbeat}; M1's device row is M1's), answers with the
 * clock offset, the location's snapshot version, the cursor and what the device should do.
 *
 * <pre>
 *   clock drift over sync.clock_drift.review_after   SYNC_CLOCK_DRIFT (REVIEW), once per drift;
 *                                                    never blocks (doc 32 section 6)
 *   the device holds more acknowledged than central  central was restored from backup (doc 32
 *                                                    section 7): SYNC_CURSOR_BEHIND (REVIEW) and
 *                                                    RESEND_FROM cursor + 1 until the cursor passes
 *   application below the floor                      FLOOR_NOTICE; its health is still recorded
 *   an urgent change after the device's version      urgent_change: download now (doc 32 section 5.2)
 * </pre>
 */
@Component
public class HeartbeatService {

    static final String AUDIT_CLOCK_DRIFT = "SYNC_CLOCK_DRIFT";
    static final String AUDIT_CURSOR_BEHIND = "SYNC_CURSOR_BEHIND";

    /** What the till reports (the slice's Heartbeat). */
    record Report(
            String appVersion,
            Long snapshotVersion,
            Integer pendingEventCount,
            Long oldestPendingSeqAgeS,
            Long lastAcknowledgedSeq,
            Integer batteryPct,
            Long storageFreeMb,
            Map<String, Object> peripherals,
            Instant deviceClock,
            Long deviceUptimeS,
            Boolean openSession) {}

    /** What central answers. */
    record Answer(
            Instant serverTime,
            Long clockOffsetMs,
            long snapshotVersion,
            long lastAppliedSeq,
            boolean urgentChange,
            List<Ack.Instruction> instructions) {}

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private final SyncSettings settings;

    HeartbeatService(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            ObjectMapper json,
            Clock clock,
            SyncSettings settings) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.settings = settings;
    }

    @Transactional
    public Answer record(ScopeContext device, Report report) {
        UUID deviceId = device.deviceId();
        List<Map<String, Object>> cursors = jdbc.queryForList(
                "select last_applied_seq, resend_from_seq from kernel.device_sync_cursor where device_id = ? for update",
                deviceId);
        if (cursors.isEmpty()) {
            throw new ProblemException("sync.device_not_enrolled");
        }
        long lastApplied = ((Number) cursors.getFirst().get("last_applied_seq")).longValue();
        Number resendFromValue = (Number) cursors.getFirst().get("resend_from_seq");
        Long resendFrom = resendFromValue == null ? null : resendFromValue.longValue();

        Instant now = clock.instant();
        Long offset = report.deviceClock() == null
                ? null
                : Duration.between(report.deviceClock(), now).toMillis();
        List<Long> previous = jdbc.queryForList(
                "select clock_offset_ms from kernel.device_heartbeat where device_id = ?", Long.class, deviceId);
        Long previousOffset = previous.isEmpty() ? null : previous.getFirst();

        jdbc.update(
                """
                insert into kernel.device_heartbeat (device_id, owner_entity_id, last_seen_at, app_version,
                    snapshot_version, pending_event_count, oldest_pending_seq_age_s, last_acknowledged_seq,
                    battery_pct, storage_free_mb, peripherals, device_clock, device_uptime_s, open_session,
                    clock_offset_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (device_id) do update set
                    last_seen_at = excluded.last_seen_at, app_version = excluded.app_version,
                    snapshot_version = excluded.snapshot_version, pending_event_count = excluded.pending_event_count,
                    oldest_pending_seq_age_s = excluded.oldest_pending_seq_age_s,
                    last_acknowledged_seq = excluded.last_acknowledged_seq, battery_pct = excluded.battery_pct,
                    storage_free_mb = excluded.storage_free_mb, peripherals = excluded.peripherals,
                    device_clock = excluded.device_clock, device_uptime_s = excluded.device_uptime_s,
                    open_session = excluded.open_session, clock_offset_ms = excluded.clock_offset_ms
                """,
                deviceId,
                device.entityId(),
                Timestamp.from(now),
                report.appVersion(),
                report.snapshotVersion(),
                report.pendingEventCount(),
                report.oldestPendingSeqAgeS(),
                report.lastAcknowledgedSeq(),
                report.batteryPct(),
                report.storageFreeMb(),
                peripherals(report.peripherals()),
                report.deviceClock() == null ? null : Timestamp.from(report.deviceClock()),
                report.deviceUptimeS(),
                report.openSession(),
                offset);

        long threshold = settings.clockDriftReviewAfter(device).toMillis();
        if (offset != null
                && Math.abs(offset) > threshold
                && (previousOffset == null || Math.abs(previousOffset) <= threshold)) {
            audit.record(
                    AUDIT_CLOCK_DRIFT,
                    Subject.of("device", deviceId),
                    null,
                    Map.of("clockOffsetMs", offset),
                    device,
                    "Till clock drift");
            events.publish(new SyncAnomaly(Ids.next(), deviceId, "CLOCK_DRIFT", null, null));
        }

        List<Ack.Instruction> instructions = new ArrayList<>();
        if (report.lastAcknowledgedSeq() != null && report.lastAcknowledgedSeq() > lastApplied) {
            long from = lastApplied + 1;
            if (resendFrom == null || resendFrom != from) {
                jdbc.update(
                        "update kernel.device_sync_cursor set resend_from_seq = ? where device_id = ?", from, deviceId);
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("lastAppliedSeq", lastApplied);
                after.put("deviceAcknowledgedSeq", report.lastAcknowledgedSeq());
                after.put("resendFromSeq", from);
                audit.record(
                        AUDIT_CURSOR_BEHIND,
                        Subject.of("device", deviceId),
                        null,
                        after,
                        device,
                        "Central holds less than the device was acknowledged");
                events.publish(new SyncAnomaly(Ids.next(), deviceId, "CURSOR_BEHIND", from, null));
            }
            resendFrom = from;
        }
        if (resendFrom != null) {
            instructions.add(Ack.Instruction.resendFrom(resendFrom));
        }
        String floor = settings.appVersionFloor(device);
        if (!AppVersions.atLeast(report.appVersion(), floor)) {
            instructions.add(Ack.Instruction.floorNotice(floor));
        }

        long snapshotVersion = 0;
        boolean urgent = false;
        if (device.locationId() != null) {
            List<Long> version = jdbc.queryForList(
                    "select current_version from kernel.location_snapshot_version where location_id = ?",
                    Long.class,
                    device.locationId());
            snapshotVersion = version.isEmpty() ? 0 : version.getFirst();
            urgent = Boolean.TRUE.equals(jdbc.queryForObject(
                    """
                    select exists (select 1 from kernel.change_log
                                    where location_id = ? and version > ? and urgent)
                    """,
                    Boolean.class,
                    device.locationId(),
                    report.snapshotVersion() == null ? 0L : report.snapshotVersion()));
        }
        return new Answer(now, offset, snapshotVersion, lastApplied, urgent, instructions);
    }

    private String peripherals(Map<String, Object> peripherals) {
        if (peripherals == null) {
            return null;
        }
        try {
            return json.writeValueAsString(peripherals);
        } catch (JsonProcessingException e) {
            throw new ProblemException("request.malformed");
        }
    }
}
