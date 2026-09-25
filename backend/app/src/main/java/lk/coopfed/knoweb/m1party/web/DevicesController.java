package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.AssignDeviceToPosition;
import lk.coopfed.knoweb.m1party.api.EnrolDevice;
import lk.coopfed.knoweb.m1party.api.ReinstateDevice;
import lk.coopfed.knoweb.m1party.api.RetireDevice;
import lk.coopfed.knoweb.m1party.api.SuspendDevice;
import lk.coopfed.knoweb.m1party.query.DeviceFilter;
import lk.coopfed.knoweb.m1party.query.DeviceQueries;
import lk.coopfed.knoweb.m1party.query.DeviceView;
import lk.coopfed.knoweb.m1party.web.generated.AssignDeviceRequest;
import lk.coopfed.knoweb.m1party.web.generated.DeviceReasonRequest;
import lk.coopfed.knoweb.m1party.web.generated.DeviceResponse;
import lk.coopfed.knoweb.m1party.web.generated.DevicesApi;
import lk.coopfed.knoweb.m1party.web.generated.EnrolDeviceRequest;
import lk.coopfed.knoweb.m1party.web.generated.SuspendDeviceRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The asset register's operations (21A section 5, /v1/party/devices; M1-06). */
@RestController
class DevicesController implements DevicesApi {

    private final Handles<EnrolDevice, UUID> enrolDevice;
    private final Handles<AssignDeviceToPosition, UUID> assignDevice;
    private final Handles<SuspendDevice, UUID> suspendDevice;
    private final Handles<ReinstateDevice, UUID> reinstateDevice;
    private final Handles<RetireDevice, UUID> retireDevice;
    private final DeviceQueries queries;
    private final CurrentScope currentScope;

    DevicesController(
            Handles<EnrolDevice, UUID> enrolDevice,
            Handles<AssignDeviceToPosition, UUID> assignDevice,
            Handles<SuspendDevice, UUID> suspendDevice,
            Handles<ReinstateDevice, UUID> reinstateDevice,
            Handles<RetireDevice, UUID> retireDevice,
            DeviceQueries queries,
            CurrentScope currentScope) {
        this.enrolDevice = enrolDevice;
        this.assignDevice = assignDevice;
        this.suspendDevice = suspendDevice;
        this.reinstateDevice = reinstateDevice;
        this.retireDevice = retireDevice;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<DeviceResponse> enrolDevice(String idempotencyKey, EnrolDeviceRequest request) {
        ScopeContext scope = currentScope.get();
        UUID deviceId = enrolDevice.handle(
                new EnrolDevice(
                        request.getHardwareSerial(),
                        request.getDeviceKind().getValue(),
                        request.getLocationId(),
                        request.getAppVersion(),
                        request.getStagingReference()),
                scope);
        DeviceView created = queries.getDevice(deviceId, scope).orElseThrow();
        return ResponseEntity.created(URI.create(DevicesApi.PATH_ENROL_DEVICE + "/" + deviceId))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<DeviceResponse> assignDeviceToPosition(
            UUID deviceId, String idempotencyKey, AssignDeviceRequest request) {
        ScopeContext scope = currentScope.get();
        assignDevice.handle(
                new AssignDeviceToPosition(
                        deviceId,
                        request.getTillPositionId(),
                        request.getReasonCode(),
                        request.getReasonText(),
                        Boolean.TRUE.equals(request.getOutboxLossRecorded())),
                scope);
        return ResponseEntity.ok(toResponse(queries.getDevice(deviceId, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<Void> suspendDevice(UUID deviceId, String idempotencyKey, SuspendDeviceRequest request) {
        suspendDevice.handle(
                new SuspendDevice(deviceId, request.getReasonCode().getValue(), request.getReasonText()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> reinstateDevice(UUID deviceId, String idempotencyKey, DeviceReasonRequest request) {
        reinstateDevice.handle(
                new ReinstateDevice(deviceId, request.getReasonCode(), request.getReasonText()), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> retireDevice(UUID deviceId, String idempotencyKey, DeviceReasonRequest request) {
        retireDevice.handle(
                new RetireDevice(deviceId, request.getReasonCode(), request.getReasonText()), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DeviceResponse> getDevice(UUID deviceId) {
        return queries.getDevice(deviceId, currentScope.get())
                .map(DevicesController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<List<DeviceResponse>> listDevices(UUID locationId, String status) {
        List<DeviceResponse> devices =
                queries.listDevices(new DeviceFilter(locationId, status), currentScope.get()).stream()
                        .map(DevicesController::toResponse)
                        .toList();
        return ResponseEntity.ok(devices);
    }

    private static DeviceResponse toResponse(DeviceView view) {
        DeviceResponse response = new DeviceResponse(
                view.deviceId(),
                view.ownerEntityId(),
                view.locationId(),
                view.hardwareSerial(),
                DeviceResponse.DeviceKindEnum.fromValue(view.deviceKind()),
                DeviceResponse.StatusEnum.fromValue(view.status()),
                view.primaryTill());
        response.setTillPositionId(view.tillPositionId());
        response.setPositionNo(view.positionNo());
        response.setAppVersion(view.appVersion());
        response.setEnrolledAt(view.enrolledAt());
        response.setLastSeenAt(view.lastSeenAt());
        return response;
    }
}
