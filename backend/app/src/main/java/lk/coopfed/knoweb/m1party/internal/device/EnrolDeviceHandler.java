package lk.coopfed.knoweb.m1party.internal.device;

import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.DeviceEnrolled;
import lk.coopfed.knoweb.m1party.api.EnrolDevice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, EnrolDevice: serial unique; staging record attested (doc 31); insert ENROLLED;
 * DEVICE_ENROLLED; device.enrolled.v1. The device belongs to the caller's entity and is enrolled
 * at one of its locations (V0009). Its credential is not issued here: that is the device
 * authentication of 19A K-02 and the enrol endpoint of the sync gateway (K-08, doc 32 section 8).
 */
@Service
@CommandHandler(permission = "sys.device.enrol", requiresMfa = true)
class EnrolDeviceHandler implements Handles<EnrolDevice, UUID> {

    static final String AUDIT_ENROLLED = "DEVICE_ENROLLED";

    private static final Set<String> KINDS = Set.of("POS_TERMINAL", "WORKSTATION", "DRIVER_MOBILE");

    private final DeviceRepository devices;
    private final DevicePlaces places;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    EnrolDeviceHandler(
            DeviceRepository devices, DevicePlaces places, Clock clock, AuditFacade audit, EventPublisher events) {
        this.devices = devices;
        this.places = places;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(EnrolDevice command, ScopeContext scope) {

        DeviceGuards.requireOwnScope(scope);

        // 1. the location is one of the caller's, and in its scope.
        DevicePlaces.Place location = command.locationId() == null
                ? null
                : places.location(command.locationId())
                        .filter(place -> place.ownerEntityId().equals(scope.entityId()))
                        .orElse(null);
        if (location == null) {
            throw new ProblemException(
                    "m1.device.location_not_found", Map.of("locationId", String.valueOf(command.locationId())));
        }
        // 2. a known kind.
        if (command.deviceKind() == null || !KINDS.contains(command.deviceKind())) {
            throw new ProblemException(
                    "m1.device.kind_invalid", Map.of("deviceKind", String.valueOf(command.deviceKind())));
        }
        // 3. the management agent's staging record attests the device (doc 31 section 4).
        if (command.stagingReference() == null || command.stagingReference().isBlank()) {
            throw new ProblemException("m1.device.not_attested");
        }
        // 4. the release it was provisioned with, which the version floor is compared with.
        if (!AppVersion.isWellFormed(command.appVersion())) {
            throw new ProblemException(
                    "m1.device.app_version_invalid", Map.of("appVersion", String.valueOf(command.appVersion())));
        }
        // 5. the serial is not enrolled yet (across the federation: the unique key is the
        //    backstop for a serial another entity holds, which this caller cannot see).
        String serial =
                command.hardwareSerial() == null ? "" : command.hardwareSerial().strip();
        if (serial.isEmpty()) {
            throw new ProblemException("m1.device.serial_required");
        }
        if (devices.existsByHardwareSerial(serial)) {
            throw duplicateSerial(serial);
        }

        Device device = Device.enrol(
                Ids.next(),
                serial,
                command.deviceKind(),
                scope.entityId(),
                location.locationId(),
                command.appVersion(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            devices.saveAndFlush(device);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw duplicateSerial(serial);
            }
            throw ex;
        }

        Map<String, Object> after = new LinkedHashMap<>(device.auditState());
        after.put("stagingReference", command.stagingReference().strip());
        audit.record(AUDIT_ENROLLED, Subject.of("device", device.getId()), null, after, scope);
        events.publish(new DeviceEnrolled(
                device.getId(),
                device.ownerEntityId(),
                device.locationId(),
                device.hardwareSerial(),
                device.deviceKind(),
                device.appVersion(),
                device.status()));

        return device.getId();
    }

    private static ProblemException duplicateSerial(String serial) {
        return new ProblemException("m1.device.serial_duplicate", Map.of("hardwareSerial", serial));
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
