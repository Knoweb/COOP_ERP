package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AppVersionFloor;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import lk.coopfed.knoweb.kernel.sync.web.generated.ChangeEntry;
import lk.coopfed.knoweb.kernel.sync.web.generated.ChangePage;
import lk.coopfed.knoweb.kernel.sync.web.generated.DeviceCredential;
import lk.coopfed.knoweb.kernel.sync.web.generated.DeviceSyncStatus;
import lk.coopfed.knoweb.kernel.sync.web.generated.EnrolmentCode;
import lk.coopfed.knoweb.kernel.sync.web.generated.EnrolmentRequest;
import lk.coopfed.knoweb.kernel.sync.web.generated.EnrolmentResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.EventOutcome;
import lk.coopfed.knoweb.kernel.sync.web.generated.Heartbeat;
import lk.coopfed.knoweb.kernel.sync.web.generated.HeartbeatResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.Instruction;
import lk.coopfed.knoweb.kernel.sync.web.generated.PresignRequest;
import lk.coopfed.knoweb.kernel.sync.web.generated.PresignResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.QuarantineResolution;
import lk.coopfed.knoweb.kernel.sync.web.generated.QuarantinedEvent;
import lk.coopfed.knoweb.kernel.sync.web.generated.ResolvedQuarantine;
import lk.coopfed.knoweb.kernel.sync.web.generated.SequenceGap;
import lk.coopfed.knoweb.kernel.sync.web.generated.SequenceResetRequest;
import lk.coopfed.knoweb.kernel.sync.web.generated.SeriesAssignment;
import lk.coopfed.knoweb.kernel.sync.web.generated.SigningKey;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotDelta;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotPointer;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotRow;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotTable;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotTombstone;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncApi;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncBatch;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sync API of doc 32 (openapi/sync.yaml; 19A section 8). The caller of a device operation is
 * a device whose token the scope filter has checked (cls = DEVICE, status ACTIVE, the device's
 * scope); here the path must name that device and its shop.
 */
@RestController
class SyncController implements SyncApi {

    private final CurrentScope currentScope;
    private final DeviceDirectory directory;
    private final EnrolmentService enrolment;
    private final SequenceReset sequenceReset;
    private final BatchIngestor ingestor;
    private final HeartbeatService heartbeats;
    private final ChangeLogReader changeLog;
    private final SnapshotBuilder snapshots;
    private final SyncRateLimiter rateLimiter;
    private final SyncSettings settings;
    private final TillSigner signer;
    private final Attachments attachments;
    private final SystemScope transactions;
    private final ObjectMapper json;
    private final HttpServletRequest request;
    private final AppVersionFloors floors;
    private final ResolveQuarantineHandler quarantine;
    private final JdbcTemplate jdbc;

    SyncController(
            CurrentScope currentScope,
            DeviceDirectory directory,
            EnrolmentService enrolment,
            SequenceReset sequenceReset,
            BatchIngestor ingestor,
            HeartbeatService heartbeats,
            ChangeLogReader changeLog,
            SnapshotBuilder snapshots,
            SyncRateLimiter rateLimiter,
            SyncSettings settings,
            TillSigner signer,
            Attachments attachments,
            SystemScope transactions,
            ObjectMapper json,
            HttpServletRequest request,
            AppVersionFloors floors,
            ResolveQuarantineHandler quarantine,
            JdbcTemplate jdbc) {
        this.floors = floors;
        this.quarantine = quarantine;
        this.jdbc = jdbc;
        this.currentScope = currentScope;
        this.directory = directory;
        this.enrolment = enrolment;
        this.sequenceReset = sequenceReset;
        this.ingestor = ingestor;
        this.heartbeats = heartbeats;
        this.changeLog = changeLog;
        this.snapshots = snapshots;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.signer = signer;
        this.attachments = attachments;
        this.transactions = transactions;
        this.json = json;
        this.request = request;
    }

    @Override
    public ResponseEntity<EnrolmentCode> issueEnrolmentCode(UUID deviceId, String idempotencyKey) {
        EnrolmentService.IssuedCode issued = enrolment.issueCode(currentScope.get(), deviceId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EnrolmentCode(issued.deviceId(), issued.code(), issued.expiresAt()));
    }

    @Override
    public ResponseEntity<EnrolmentResponse> enrolTill(
            UUID deviceId, String idempotencyKey, EnrolmentRequest enrolmentRequest) {
        EnrolmentStore.Enrolled enrolled = enrolment.enrol(
                deviceId,
                enrolmentRequest.getEnrolmentCode(),
                enrolmentRequest.getHardwareSerial(),
                enrolmentRequest.getAppVersion(),
                idempotencyKey,
                currentScope.get().correlationId());
        DeviceRecord device = enrolled.device();
        List<SeriesAssignment> series = enrolled.series().stream()
                .map(s -> new SeriesAssignment(
                        s.seriesId(),
                        s.docTypeCode(),
                        SeriesAssignment.ScopeEnum.fromValue(s.scope()),
                        s.prefix(),
                        s.nextNumber()))
                .toList();
        EnrolmentResponse body = new EnrolmentResponse(
                        device.deviceId(),
                        device.ownerEntityId(),
                        device.locationId(),
                        device.tillPositionId(),
                        device.primaryTill(),
                        new DeviceCredential(
                                enrolled.credential().clientId(),
                                enrolled.credential().clientSecret(),
                                enrolled.credential().tokenEndpoint()),
                        enrolled.nextDeviceSeq(),
                        series,
                        new SnapshotPointer(enrolled.snapshotVersion(), true),
                        new SigningKey(signer.keyId(), SigningKey.AlgorithmEnum.ED25519, signer.publicKeyBase64()))
                .positionNo(device.positionNo());
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<SequenceGap> resetDeviceSequence(
            UUID deviceId, String idempotencyKey, SequenceResetRequest sequenceResetRequest) {
        ScopeContext admin = currentScope.get();
        // M1's device, read outside the transaction as the enrolment code does; the reset checks
        // that it is of the administrator's entity.
        DeviceRecord device = directory.findFresh(deviceId).orElse(null);
        SequenceReset.Gap gap = sequenceReset.reset(
                admin,
                device,
                sequenceResetRequest.getNewStartSeq(),
                sequenceResetRequest.getReasonCode(),
                sequenceResetRequest.getReasonText(),
                idempotencyKey,
                settings.inFlightTimeout(admin));
        return ResponseEntity.ok(
                new SequenceGap(gap.gapId(), gap.deviceId(), gap.fromSeq(), gap.toSeq(), gap.recordedAt()));
    }

    @Override
    public ResponseEntity<ResolvedQuarantine> resolveQuarantine(
            UUID quarantineId, String idempotencyKey, QuarantineResolution resolution) {
        ResolveQuarantineHandler.Resolved resolved = quarantine.resolve(
                currentScope.get(),
                quarantineId,
                resolution.getResolution().getValue(),
                resolution.getReasonCode(),
                resolution.getReasonText());
        return ResponseEntity.ok(new ResolvedQuarantine(
                resolved.quarantineId(),
                resolved.deviceId(),
                resolved.deviceSeq(),
                resolved.reason(),
                ResolvedQuarantine.ResolutionEnum.fromValue(resolved.resolution()),
                resolved.resolvedAt()));
    }

    @Override
    public ResponseEntity<DeviceSyncStatus> getDeviceSyncStatus(UUID deviceId) {
        ScopeContext scope = currentScope.get();
        DeviceRecord dev = directory.find(deviceId).orElseThrow(() -> new ProblemException("sync.device.not_found"));
        if (scope.policyClass() != PolicyClass.FEDERATION_VIEW
                && (scope.policyClass() != PolicyClass.OWN
                        || !dev.ownerEntityId().equals(scope.entityId()))) {
            throw new ProblemException("sync.device.not_found");
        }
        return transactions.inScope(scope, () -> {
            String sql =
                    """
                    select c.device_id, c.owner_entity_id, c.last_applied_seq, c.last_applied_at,
                           c.last_batch_id, c.in_flight_batch_id, c.in_flight_since, c.gap_rejections,
                           c.resend_from_seq, c.snapshot_version_reported,
                           coalesce(h.app_version, c.app_version) as app_version, c.last_enrolled_at,
                           h.last_seen_at, h.battery_pct, h.storage_free_mb, h.pending_event_count,
                           h.last_acknowledged_seq, h.clock_offset_ms, h.open_session
                      from kernel.device_sync_cursor c
                      left join kernel.device_heartbeat h on h.device_id = c.device_id
                     where c.device_id = ?
                    """;
            List<DeviceSyncStatus> statuses = jdbc.query(
                    sql,
                    (rs, rowNum) -> {
                        UUID devId = (UUID) rs.getObject("device_id");
                        UUID ownerId = (UUID) rs.getObject("owner_entity_id");
                        long appliedSeq = rs.getLong("last_applied_seq");
                        int gapRej = rs.getInt("gap_rejections");

                        DeviceSyncStatus status = new DeviceSyncStatus(devId, ownerId, appliedSeq, gapRej);
                        Timestamp appliedAt = rs.getTimestamp("last_applied_at");
                        status.setLastAppliedAt(appliedAt != null ? appliedAt.toInstant() : null);
                        status.setLastBatchId((UUID) rs.getObject("last_batch_id"));
                        status.setInFlightBatchId((UUID) rs.getObject("in_flight_batch_id"));
                        Timestamp inFlightSince = rs.getTimestamp("in_flight_since");
                        status.setInFlightSince(inFlightSince != null ? inFlightSince.toInstant() : null);

                        long resendFrom = rs.getLong("resend_from_seq");
                        status.setResendFromSeq(rs.wasNull() ? null : resendFrom);

                        long snapRep = rs.getLong("snapshot_version_reported");
                        status.setSnapshotVersionReported(rs.wasNull() ? null : snapRep);

                        status.setAppVersion(rs.getString("app_version"));
                        Timestamp enrolledAt = rs.getTimestamp("last_enrolled_at");
                        status.setLastEnrolledAt(enrolledAt != null ? enrolledAt.toInstant() : null);

                        Timestamp seenAt = rs.getTimestamp("last_seen_at");
                        status.setLastSeenAt(seenAt != null ? seenAt.toInstant() : null);

                        int battery = rs.getInt("battery_pct");
                        status.setBatteryPct(rs.wasNull() ? null : battery);

                        long storage = rs.getLong("storage_free_mb");
                        status.setStorageFreeMb(rs.wasNull() ? null : storage);

                        int pendingEvents = rs.getInt("pending_event_count");
                        status.setPendingEventCount(rs.wasNull() ? null : pendingEvents);

                        long lastAckSeq = rs.getLong("last_acknowledged_seq");
                        status.setLastAcknowledgedSeq(rs.wasNull() ? null : lastAckSeq);

                        long clockOffset = rs.getLong("clock_offset_ms");
                        status.setClockOffsetMs(rs.wasNull() ? null : clockOffset);

                        boolean openSession = rs.getBoolean("open_session");
                        status.setOpenSession(rs.wasNull() ? null : openSession);

                        return status;
                    },
                    deviceId);

            DeviceSyncStatus status;
            if (statuses.isEmpty()) {
                status = new DeviceSyncStatus(dev.deviceId(), dev.ownerEntityId(), 0L, 0);
                status.setRecentGaps(List.of());
                return ResponseEntity.ok(status);
            } else {
                status = statuses.getFirst();
            }

            List<SequenceGap> gaps = jdbc.query(
                    """
                    select gap_id, device_id, from_seq, to_seq, recorded_at
                      from kernel.sync_sequence_gap
                     where device_id = ?
                     order by recorded_at desc
                     limit 10
                    """,
                    (rs, rowNum) -> {
                        UUID gapId = (UUID) rs.getObject("gap_id");
                        UUID devId = (UUID) rs.getObject("device_id");
                        long fromSeq = rs.getLong("from_seq");
                        long toSeq = rs.getLong("to_seq");
                        Timestamp recAt = rs.getTimestamp("recorded_at");
                        return new SequenceGap(gapId, devId, fromSeq, toSeq, recAt != null ? recAt.toInstant() : null);
                    },
                    deviceId);
            status.setRecentGaps(gaps);
            return ResponseEntity.ok(status);
        });
    }

    @Override
    public ResponseEntity<List<QuarantinedEvent>> listQuarantine(
            UUID deviceId, Boolean resolved, Integer limit, Integer offset) {
        ScopeContext scope = currentScope.get();
        return transactions.inScope(scope, () -> {
            StringBuilder sql = new StringBuilder(
                    """
                    select quarantine_id, device_id, device_seq, owner_entity_id, location_id, batch_id,
                           event_id, event_type, reason, detail, raw_event, received_at,
                           resolved_at, resolved_by_user_id, resolution, resolution_reason,
                           series_id, doc_number
                      from kernel.sync_quarantine
                     where 1=1
                    """);
            List<Object> params = new ArrayList<>();
            if (deviceId != null) {
                sql.append(" and device_id = ?");
                params.add(deviceId);
            }
            if (resolved != null) {
                if (resolved) {
                    sql.append(" and resolved_at is not null");
                } else {
                    sql.append(" and resolved_at is null");
                }
            }
            sql.append(" order by received_at desc, device_seq desc limit ? offset ?");
            params.add(limit != null ? limit : 100);
            params.add(offset != null ? offset : 0);

            List<QuarantinedEvent> events =
                    jdbc.query(sql.toString(), (rs, rowNum) -> mapQuarantinedEvent(rs), params.toArray());
            return ResponseEntity.ok(events);
        });
    }

    @Override
    public ResponseEntity<QuarantinedEvent> getQuarantine(UUID quarantineId) {
        ScopeContext scope = currentScope.get();
        return transactions.inScope(scope, () -> {
            String sql =
                    """
                    select quarantine_id, device_id, device_seq, owner_entity_id, location_id, batch_id,
                           event_id, event_type, reason, detail, raw_event, received_at,
                           resolved_at, resolved_by_user_id, resolution, resolution_reason,
                           series_id, doc_number
                      from kernel.sync_quarantine
                     where quarantine_id = ?
                    """;
            List<QuarantinedEvent> rows = jdbc.query(sql, (rs, rowNum) -> mapQuarantinedEvent(rs), quarantineId);
            if (rows.isEmpty()) {
                throw new ProblemException("sync.quarantine.not_found");
            }
            return ResponseEntity.ok(rows.getFirst());
        });
    }

    private QuarantinedEvent mapQuarantinedEvent(ResultSet rs) throws SQLException {
        UUID qId = (UUID) rs.getObject("quarantine_id");
        UUID devId = (UUID) rs.getObject("device_id");
        long devSeq = rs.getLong("device_seq");
        UUID ownerId = (UUID) rs.getObject("owner_entity_id");
        UUID batchId = (UUID) rs.getObject("batch_id");
        String reasonStr = rs.getString("reason");
        QuarantinedEvent.ReasonEnum reason = QuarantinedEvent.ReasonEnum.fromValue(reasonStr);
        Timestamp recTs = rs.getTimestamp("received_at");
        Instant receivedAt = recTs != null ? recTs.toInstant() : null;

        QuarantinedEvent event = new QuarantinedEvent(qId, devId, devSeq, ownerId, batchId, reason, receivedAt);
        event.setLocationId((UUID) rs.getObject("location_id"));
        event.setEventId((UUID) rs.getObject("event_id"));
        event.setEventType(rs.getString("event_type"));
        event.setDetail(rs.getString("detail"));
        event.setRawEvent(rs.getString("raw_event"));

        Timestamp resTs = rs.getTimestamp("resolved_at");
        event.setResolvedAt(resTs != null ? resTs.toInstant() : null);
        event.setResolvedByUserId((UUID) rs.getObject("resolved_by_user_id"));
        String resStr = rs.getString("resolution");
        event.setResolution(resStr != null ? QuarantinedEvent.ResolutionEnum.fromValue(resStr) : null);
        event.setResolutionReason(rs.getString("resolution_reason"));
        event.setSeriesId((UUID) rs.getObject("series_id"));
        long docNum = rs.getLong("doc_number");
        event.setDocNumber(rs.wasNull() ? null : docNum);
        return event;
    }

    @Override
    public ResponseEntity<SyncAck> uploadBatch(UUID deviceId, String idempotencyKey, SyncBatch syncBatch) {
        ScopeContext device = deviceScope(deviceId);
        DeviceRecord record = directory.find(deviceId).orElseThrow(() -> new ProblemException("sync.device_unknown"));
        List<JsonNode> events = syncBatch.getEvents().stream()
                .map(event -> (JsonNode) json.valueToTree(event))
                .toList();
        long bytes = wireBytes();
        Ack ack;
        // 429 sync.rate_limited before anything is read or written (doc 32 section 9).
        try (SyncRateLimiter.Permit permit = rateLimiter.admit(device, deviceId, bytes)) {
            ack = ingestor.ingest(
                    device,
                    record,
                    new BatchIngestor.BatchInput(
                            syncBatch.getBatchId(),
                            syncBatch.getFirstSeq(),
                            syncBatch.getLastSeq(),
                            syncBatch.getAppVersion(),
                            syncBatch.getSnapshotVersionInUse(),
                            syncBatch.getDeviceClock(),
                            events,
                            bytes));
        }
        SyncAck body = new SyncAck(
                        ack.batchId(),
                        ack.lastAppliedSeq(),
                        ack.outcomes().stream().map(SyncController::outcome).toList(),
                        ack.serverTime(),
                        ack.snapshotVersion(),
                        instructions(ack.instructions()))
                .clockOffsetMs(ack.clockOffsetMs());
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<HeartbeatResponse> heartbeat(UUID deviceId, String idempotencyKey, Heartbeat heartbeat) {
        ScopeContext device = deviceScope(deviceId);
        rateLimiter.admitRequest(device, deviceId, false);
        HeartbeatService.Answer answer = heartbeats.record(
                device,
                new HeartbeatService.Report(
                        heartbeat.getAppVersion(),
                        heartbeat.getSnapshotVersion(),
                        heartbeat.getPendingEventCount(),
                        heartbeat.getOldestPendingSeqAgeS(),
                        heartbeat.getLastAcknowledgedSeq(),
                        heartbeat.getBatteryPct(),
                        heartbeat.getStorageFreeMb(),
                        heartbeat.getPeripherals(),
                        heartbeat.getDeviceClock(),
                        heartbeat.getDeviceUptimeS(),
                        heartbeat.getOpenSession()));
        HeartbeatResponse body = new HeartbeatResponse(
                        answer.serverTime(),
                        answer.snapshotVersion(),
                        answer.lastAppliedSeq(),
                        answer.urgentChange(),
                        instructions(answer.instructions()))
                .clockOffsetMs(answer.clockOffsetMs());
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<ChangePage> listChanges(UUID locationId, Long since, Integer limit) {
        ScopeContext device = locationScope(locationId);
        rateLimiter.admitRequest(device, device.deviceId(), false);
        withheldBelowTheFloor(device);
        ChangeLogReader.Page page =
                changeLog.read(device, since, limit == null ? 500 : limit, settings.changeLogRetention(device));
        List<ChangeEntry> entries = page.entries().stream()
                .map(e -> new ChangeEntry(
                                e.version(),
                                e.table(),
                                e.rowId(),
                                ChangeEntry.OpEnum.fromValue(e.op()),
                                e.urgent(),
                                e.recordedAt())
                        .applyFrom(e.applyFrom()))
                .toList();
        return ResponseEntity.ok(new ChangePage(
                page.locationId(),
                page.since(),
                page.currentVersion(),
                page.fullSnapshotRequired(),
                page.nextSince(),
                page.hasMore(),
                entries));
    }

    @Override
    public ResponseEntity<SnapshotDelta> getSnapshot(UUID locationId, Long since) {
        ScopeContext device = locationScope(locationId);
        rateLimiter.admitRequest(device, device.deviceId(), true);
        withheldBelowTheFloor(device);
        SnapshotBuilder.Snapshot snapshot =
                snapshots.build(device, since == null ? 0 : since, settings.changeLogRetention(device));
        Map<String, SnapshotTable> tables = new LinkedHashMap<>();
        snapshot.tables().forEach((name, table) -> tables.put(name, snapshotTable(table)));
        SnapshotDelta body = new SnapshotDelta(
                        snapshot.locationId(),
                        snapshot.version(),
                        snapshot.full(),
                        snapshot.urgent(),
                        tables,
                        snapshot.manifest(),
                        snapshot.signature(),
                        snapshot.keyId())
                .since(snapshot.since());
        // Downloads are the heavy direction (a full snapshot of a shop's catalogue): what is sent
        // costs the device's hourly byte bucket as an upload does (wave 2, TWK-25).
        rateLimiter.chargeBytes(device, device.deviceId(), answerBytes(body));
        return ResponseEntity.ok(body);
    }

    /** The size of the answer as JSON, before any compression on the way. */
    private long answerBytes(Object body) {
        try {
            return json.writeValueAsBytes(body).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The snapshot answer cannot be written as JSON", e);
        }
    }

    /**
     * After the grace of the application floor, central withholds its reference data and keeps
     * taking the device's facts (CR-30-1 point 4; CR-32-1 item 1; wave 2, TWK-09): the till sells
     * on its last snapshot, and its staleness warning tells the supervisor (doc 32 section 5.2).
     */
    private void withheldBelowTheFloor(ScopeContext device) {
        AppVersionFloor.Standing floor = floors.standingOfDevice(device);
        if (floor.afterGrace()) {
            throw new ProblemException(
                    "sync.app_below_floor",
                    Map.of(
                            "floor",
                            floor.floor(),
                            "grace_ended_at",
                            floor.graceEndsAt().toString()));
        }
    }

    private static SnapshotTable snapshotTable(SnapshotManifest.Table table) {
        List<SnapshotRow> upserts = table.upserts().stream()
                .map(row -> new SnapshotRow(row.rowId(), row.data()).applyFrom(row.applyFrom()))
                .toList();
        List<SnapshotTombstone> tombstones = table.tombstones().stream()
                .map(gone -> new SnapshotTombstone(gone.rowId()).applyFrom(gone.applyFrom()))
                .toList();
        return new SnapshotTable(upserts, tombstones);
    }

    @Override
    public ResponseEntity<PresignResponse> presignAttachment(String idempotencyKey, PresignRequest presignRequest) {
        ScopeContext device = currentScope.get();
        rateLimiter.admitRequest(device, device.deviceId(), false);
        // The attachment service writes its pending row in a transaction under the caller's scope
        // (K-09); the device's scope is applied to that transaction as to any handler's.
        Attachments.PresignedUpload upload = transactions.inScope(
                device,
                () -> attachments.presignUpload(
                        presignRequest.getDocumentId(),
                        presignRequest.getAttachmentId(),
                        presignRequest.getContentType(),
                        presignRequest.getContentLength(),
                        presignRequest.getSha256(),
                        device));
        return ResponseEntity.ok(new PresignResponse(
                upload.attachmentId(), upload.url().toString(), upload.expiresAt(), upload.objectKey()));
    }

    /** The device of the token must be the device of the path (403 sync.device_mismatch). */
    private ScopeContext deviceScope(UUID deviceId) {
        ScopeContext scope = currentScope.get();
        if (scope.deviceId() == null || !scope.deviceId().equals(deviceId)) {
            throw new ProblemException("sync.device_mismatch");
        }
        return scope;
    }

    /** A device reads its own shop only (403 sync.location_mismatch). */
    private ScopeContext locationScope(UUID locationId) {
        ScopeContext scope = currentScope.get();
        if (scope.locationId() == null || !scope.locationId().equals(locationId)) {
            throw new ProblemException("sync.location_mismatch");
        }
        return scope;
    }

    /**
     * The batch's size as the device sent it, compressed when it was, as {@link SyncBodyFilter}
     * counted the bytes it read (wave 2, TWK-24: a plain or chunked body is counted too).
     */
    private long wireBytes() {
        if (request.getAttribute(SyncBodyFilter.WIRE_BYTES) instanceof Long read) {
            return read;
        }
        return Math.max(0, request.getContentLengthLong());
    }

    private static EventOutcome outcome(Ack.Outcome outcome) {
        return new EventOutcome(outcome.deviceSeq(), EventOutcome.OutcomeEnum.fromValue(outcome.outcome()))
                .eventId(outcome.eventId())
                .reason(outcome.reason());
    }

    private static List<Instruction> instructions(List<Ack.Instruction> instructions) {
        return instructions.stream()
                .map(i -> new Instruction(Instruction.TypeEnum.fromValue(i.type()))
                        .fromSeq(i.fromSeq())
                        .detail(i.detail()))
                .toList();
    }
}
