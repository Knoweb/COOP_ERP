package lk.coopfed.knoweb.m1party.internal.device;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncStatus;
import lk.coopfed.knoweb.m1party.api.AssignDeviceToPosition;
import lk.coopfed.knoweb.m1party.api.DeviceAssigned;
import lk.coopfed.knoweb.m1party.api.DevicePositionChanged;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6 and the pseudocode of 6.1, AssignDeviceToPosition: device ENROLLED or ACTIVE;
 * position free; app_version at or above the floor; on re-assign the outbox of the device that
 * gives a lane up is drained ({@link SyncStatus}) or its loss recorded; then the device takes
 * the position, the counters of the position (and of the shop, when the position is its
 * primary till) move to it through the kernel ({@link NumberingService#holderChange}, which
 * audits and publishes series.holder_changed.v1), DEVICE_ASSIGNED or DEVICE_POSITION_CHANGED,
 * device.assigned.v1 or device.position_changed.v1.
 *
 * <p>Where the pseudocode is read closely. "Position free" and "the device that previously held
 * the position" are both true in the replacement of doc 21 flow 6.6 because a suspended device
 * keeps its position (V0009): a position held by a SUSPENDED device is free for a replacement,
 * and that device is the previous holder, taken from the database, not from the request. A
 * position held by an ACTIVE device is occupied: suspend it first. The drained check also
 * covers a device that moves to another position of its shop, since its outbox holds the
 * numbers of the lane it leaves; the series of that lane (and the shop's, when that lane was
 * the primary till) are left with no holder ({@link NumberingService#releaseHolder}) until a
 * device is assigned there again. The new number is not computed here: central's series
 * already stands at the highest applied number plus one, and the holder change is what the
 * till is told (doc 32 section 8).
 *
 * <p>The version the floor is compared with is the one the till last reported to the sync
 * gateway ({@link SyncStatus#state}); the enrolment value on M1's row is used only for a device
 * that never reported (the heartbeat writes the kernel's tables, never M1's, since K-08).
 *
 * <p>The device and the position are locked for the transaction, so that two administrators
 * assigning the same device, or two devices to the same position, run one after the other; the
 * one-device-per-position index is the backstop, answered as position_occupied.
 */
@Service
@CommandHandler(permission = "sys.device.enrol", requiresMfa = true)
class AssignDeviceToPositionHandler implements Handles<AssignDeviceToPosition, UUID> {

    static final String AUDIT_ASSIGNED = "DEVICE_ASSIGNED";
    static final String AUDIT_POSITION_CHANGED = "DEVICE_POSITION_CHANGED";
    static final String AUDIT_OUTBOX_LOSS = "DEVICE_OUTBOX_LOSS_RECORDED";

    /** The lowest release a device may run to be assigned (doc 31 section 6; config register). */
    static final String VERSION_FLOOR = "m1.device.version_floor";

    /** SQLSTATE unique_violation: the one_device_per_position index refused a second holder. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DeviceRepository devices;
    private final DevicePlaces places;
    private final ConfigRegistry config;
    private final SyncStatus sync;
    private final NumberingService numbering;
    private final AuditFacade audit;
    private final EventPublisher events;

    AssignDeviceToPositionHandler(
            DeviceRepository devices,
            DevicePlaces places,
            ConfigRegistry config,
            SyncStatus sync,
            NumberingService numbering,
            AuditFacade audit,
            EventPublisher events) {
        this.devices = devices;
        this.places = places;
        this.config = config;
        this.sync = sync;
        this.numbering = numbering;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AssignDeviceToPosition command, ScopeContext scope) {

        DeviceGuards.requireOwnScope(scope);
        Device device = DeviceGuards.lockedDevice(devices, command.deviceId());

        // 1. the device is ENROLLED or ACTIVE.
        if (!device.isEnrolled() && !device.isActive()) {
            throw new ProblemException(
                    "m1.device.not_assignable", Map.of("deviceId", device.getId(), "status", device.status()));
        }
        // 2. only a till terminal takes a till position.
        if (!device.isTill()) {
            throw new ProblemException(
                    "m1.device.not_a_till", Map.of("deviceId", device.getId(), "deviceKind", device.deviceKind()));
        }
        // 3. the position exists in the caller's scope, is ACTIVE and is at the device's location;
        //    locked, so that nobody else assigns to it or retires it meanwhile.
        DevicePlaces.Lane lane = command.tillPositionId() == null
                ? null
                : places.positionForUpdate(command.tillPositionId()).orElse(null);
        if (lane == null) {
            throw new ProblemException(
                    "m1.device.position_not_found", Map.of("tillPositionId", String.valueOf(command.tillPositionId())));
        }
        if (!lane.isActive()) {
            throw new ProblemException(
                    "m1.device.position_not_active", Map.of("tillPositionId", lane.tillPositionId()));
        }
        if (!lane.locationId().equals(device.locationId())) {
            throw new ProblemException(
                    "m1.device.position_other_location",
                    Map.of("tillPositionId", lane.tillPositionId(), "locationId", device.locationId()));
        }
        // 4. the position is free: nobody holds it, or a suspended device that a replacement relieves.
        Optional<Device> holder = devices.findByCurrentTillPositionIdForUpdate(lane.tillPositionId());
        if (holder.isPresent() && holder.get().getId().equals(device.getId())) {
            throw new ProblemException("m1.device.already_assigned", Map.of("tillPositionId", lane.tillPositionId()));
        }
        if (holder.isPresent() && !holder.get().isSuspended()) {
            throw new ProblemException(
                    "m1.device.position_occupied",
                    Map.of(
                            "tillPositionId",
                            lane.tillPositionId(),
                            "holderDeviceId",
                            holder.get().getId()));
        }
        // 5. the device runs a release at or above the floor: the release it last reported, or
        //    the one it was enrolled with when it never reported.
        String floor = config.getOrDefault(VERSION_FLOOR, scope, "0");
        String appVersion = reportedVersion(device, scope);
        if (!AppVersion.isWellFormed(appVersion) || !AppVersion.isAtLeast(appVersion, floor)) {
            throw new ProblemException(
                    "m1.device.below_floor", Map.of("appVersion", String.valueOf(appVersion), "floor", floor));
        }
        // 6. a reason.
        String reason = DeviceGuards.reason(command.reasonCode(), command.reasonText());
        // 7. whoever gives a lane up has sent everything, or the administrator records the loss.
        //    The series each undrained device gives up are the ones its unsent numbers belong to:
        //    the new lane's for the holder being relieved, the old lane's for the device moving.
        Device previous = holder.orElse(null);
        UUID previousPosition = device.currentTillPositionId();
        boolean previousWasPrimary = previousPosition != null
                && places.location(device.locationId())
                        .map(place -> previousPosition.equals(place.primaryTillPositionId()))
                        .orElse(false);
        boolean primaryTill = places.location(lane.locationId())
                .map(place -> lane.tillPositionId().equals(place.primaryTillPositionId()))
                .orElse(false);
        List<UUID> seriesTaken = seriesOf(lane.ownerEntityId(), lane.locationId(), lane.tillPositionId(), primaryTill);
        List<UUID> seriesLeft = previousPosition == null
                ? List.of()
                : seriesOf(device.ownerEntityId(), device.locationId(), previousPosition, previousWasPrimary);
        seriesLeft = new ArrayList<>(seriesLeft);
        seriesLeft.removeAll(seriesTaken);

        List<UUID> undrained = new ArrayList<>();
        List<Map<String, Object>> losses = new ArrayList<>();
        if (previous != null && !sync.drained(previous.getId())) {
            undrained.add(previous.getId());
            losses.add(loss(previous.getId(), lane.tillPositionId(), seriesTaken));
        }
        if (previousPosition != null && !sync.drained(device.getId())) {
            undrained.add(device.getId());
            losses.add(loss(device.getId(), previousPosition, seriesLeft));
        }
        if (!undrained.isEmpty() && !command.outboxLossRecorded()) {
            throw new ProblemException("m1.device.outbox_not_drained", Map.of("deviceIds", List.copyOf(undrained)));
        }
        boolean lossRecorded = !undrained.isEmpty();

        // Mutation: the old holder lets go first (one device per position), then the new one takes it.
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (previous != null) {
            before.put("previousDevice", previous.auditState());
            previous.givePositionUp();
            devices.saveAndFlush(previous);
            after.put("previousDevice", previous.auditState());
        }
        before.put("device", device.auditState());
        device.assignTo(lane.tillPositionId());
        try {
            devices.saveAndFlush(device);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, lane);
        }
        after.put("device", device.auditState());

        // The counters follow the position (ADR-11), and the shop's with its primary till; the
        // lane the device leaves keeps no holder until a device is assigned there.
        if (!seriesTaken.isEmpty()) {
            numbering.holderChange(seriesTaken, device.getId(), scope);
        }
        if (!seriesLeft.isEmpty()) {
            numbering.releaseHolder(seriesLeft, scope);
        }

        boolean changed = previous != null || previousPosition != null;
        audit.record(
                changed ? AUDIT_POSITION_CHANGED : AUDIT_ASSIGNED,
                Subject.of("device", device.getId()),
                before,
                after,
                scope,
                reason);
        if (lossRecorded) {
            // The numbers the lost outboxes took and never sent become a documented gap (doc 32
            // section 8), named per device with the series of the position it gives up; the
            // sync gateway records the range when it resets the sequence.
            List<UUID> seriesWithGaps = new ArrayList<>();
            for (Map<String, Object> loss : losses) {
                for (Object seriesId : (List<?>) loss.get("seriesIds")) {
                    seriesWithGaps.add((UUID) seriesId);
                }
            }
            Map<String, Object> recorded = new LinkedHashMap<>();
            recorded.put("deviceIds", List.copyOf(undrained));
            recorded.put("seriesIds", List.copyOf(seriesWithGaps));
            recorded.put("losses", List.copyOf(losses));
            audit.record(
                    AUDIT_OUTBOX_LOSS,
                    Subject.of("till_position", lane.tillPositionId()),
                    null,
                    recorded,
                    scope,
                    reason);
        }

        if (changed) {
            events.publish(new DevicePositionChanged(
                    device.getId(),
                    device.ownerEntityId(),
                    device.locationId(),
                    lane.tillPositionId(),
                    previous == null ? null : previous.getId(),
                    previousPosition,
                    lossRecorded,
                    List.copyOf(seriesTaken)));
        } else {
            events.publish(new DeviceAssigned(
                    device.getId(),
                    device.ownerEntityId(),
                    device.locationId(),
                    lane.tillPositionId(),
                    List.copyOf(seriesTaken)));
        }

        return device.getId();
    }

    /** The version the till last reported to the sync gateway, else the one it was enrolled with. */
    private String reportedVersion(Device device, ScopeContext scope) {
        return sync.state(device.getId(), scope)
                .map(SyncStatus.DeviceSyncState::appVersion)
                .filter(version -> version != null && !version.isBlank())
                .orElse(device.appVersion());
    }

    /** The active series of a position, and of its shop when the position is the primary till. */
    private List<UUID> seriesOf(UUID ownerEntityId, UUID locationId, UUID tillPositionId, boolean primaryTill) {
        List<UUID> series = new ArrayList<>(numbering.activeSeriesOf(ownerEntityId, locationId, tillPositionId));
        if (primaryTill) {
            series.addAll(numbering.activeSeriesOf(ownerEntityId, locationId, null));
        }
        return series;
    }

    private static Map<String, Object> loss(UUID deviceId, UUID tillPositionId, List<UUID> seriesIds) {
        Map<String, Object> loss = new LinkedHashMap<>();
        loss.put("deviceId", deviceId);
        loss.put("tillPositionId", tillPositionId);
        loss.put("seriesIds", List.copyOf(seriesIds));
        return loss;
    }

    /**
     * The backstop of the position lock: a second holder refused by one_device_per_position
     * becomes the same problem as the pre-check's; anything else is rethrown as it is.
     */
    private static RuntimeException translate(DataIntegrityViolationException ex, DevicePlaces.Lane lane) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return new ProblemException(
                        "m1.device.position_occupied", Map.of("tillPositionId", lane.tillPositionId()));
            }
            current = current.getCause();
        }
        return ex;
    }
}
