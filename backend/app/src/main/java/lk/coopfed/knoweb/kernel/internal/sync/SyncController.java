package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import lk.coopfed.knoweb.kernel.sync.web.generated.ChangeEntry;
import lk.coopfed.knoweb.kernel.sync.web.generated.ChangePage;
import lk.coopfed.knoweb.kernel.sync.web.generated.DeviceCredential;
import lk.coopfed.knoweb.kernel.sync.web.generated.EnrolmentCode;
import lk.coopfed.knoweb.kernel.sync.web.generated.EnrolmentRequest;
import lk.coopfed.knoweb.kernel.sync.web.generated.EnrolmentResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.EventOutcome;
import lk.coopfed.knoweb.kernel.sync.web.generated.Heartbeat;
import lk.coopfed.knoweb.kernel.sync.web.generated.HeartbeatResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.Instruction;
import lk.coopfed.knoweb.kernel.sync.web.generated.PresignRequest;
import lk.coopfed.knoweb.kernel.sync.web.generated.PresignResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.SeriesAssignment;
import lk.coopfed.knoweb.kernel.sync.web.generated.SigningKey;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotDelta;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotPointer;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncApi;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncBatch;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final BatchIngestor ingestor;
    private final HeartbeatService heartbeats;
    private final ChangeLogReader changeLog;
    private final SyncSettings settings;
    private final TillSigner signer;
    private final Attachments attachments;
    private final SystemScope transactions;
    private final ObjectMapper json;
    private final HttpServletRequest request;

    SyncController(
            CurrentScope currentScope,
            DeviceDirectory directory,
            EnrolmentService enrolment,
            BatchIngestor ingestor,
            HeartbeatService heartbeats,
            ChangeLogReader changeLog,
            SyncSettings settings,
            TillSigner signer,
            Attachments attachments,
            SystemScope transactions,
            ObjectMapper json,
            HttpServletRequest request) {
        this.currentScope = currentScope;
        this.directory = directory;
        this.enrolment = enrolment;
        this.ingestor = ingestor;
        this.heartbeats = heartbeats;
        this.changeLog = changeLog;
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
    public ResponseEntity<EnrolmentResponse> enrolDevice(
            UUID deviceId, String idempotencyKey, EnrolmentRequest enrolmentRequest) {
        EnrolmentStore.Enrolled enrolled = enrolment.enrol(
                deviceId,
                enrolmentRequest.getEnrolmentCode(),
                enrolmentRequest.getHardwareSerial(),
                enrolmentRequest.getAppVersion(),
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
    public ResponseEntity<SyncAck> uploadBatch(UUID deviceId, String idempotencyKey, SyncBatch syncBatch) {
        ScopeContext device = deviceScope(deviceId);
        DeviceRecord record = directory.find(deviceId).orElseThrow(() -> new ProblemException("sync.device_unknown"));
        List<JsonNode> events = syncBatch.getEvents().stream()
                .map(event -> (JsonNode) json.valueToTree(event))
                .toList();
        Ack ack = ingestor.ingest(
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
                        wireBytes()));
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
        locationScope(locationId);
        // The snapshot builder and its contributors are the second part of K-08.
        throw new ProblemException("sync.snapshot.unavailable");
    }

    @Override
    public ResponseEntity<PresignResponse> presignAttachment(String idempotencyKey, PresignRequest presignRequest) {
        ScopeContext device = currentScope.get();
        // The attachment service writes its pending row in a transaction under the caller's scope
        // (K-09); the device's scope is applied to that transaction as to any handler's.
        Attachments.PresignedUpload upload = transactions.inScope(
                device,
                () -> attachments.presignUpload(
                        presignRequest.getDocumentId(),
                        presignRequest.getAttachmentId(),
                        presignRequest.getContentType(),
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

    /** The batch's size as the device sent it: compressed when it was (GzipRequestFilter). */
    private long wireBytes() {
        if (request.getAttribute(GzipRequestFilter.WIRE_BYTES) instanceof Long compressed) {
            return compressed;
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
