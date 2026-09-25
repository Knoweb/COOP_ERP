package lk.coopfed.knoweb.m1party.internal.device;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A device: the hardware (doc 21 section 3.4). A till position is the logical lane that owns
 * the numbering series; a device holds at most one position at a time, and the position's
 * counters follow whichever device holds it (ADR-11). The life cycle is doc 21 section 4.5:
 *
 * <pre>
 *   (enrol)   -> ENROLLED
 *   ENROLLED  -> ACTIVE     assigned to a position
 *   ACTIVE    -> ACTIVE     moved to another position, or took a suspended device's position
 *   ACTIVE    -> SUSPENDED  lost, stolen, faulty; keeps its position until a replacement takes it
 *   SUSPENDED -> ACTIVE     recovered
 *   any       -> RETIRED    unassigned and wiped; for good
 * </pre>
 */
@jakarta.persistence.Entity
@Table(schema = "party", name = "device")
public class Device implements Persistable<UUID> {

    static final String STATUS_ENROLLED = "ENROLLED";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_SUSPENDED = "SUSPENDED";
    static final String STATUS_RETIRED = "RETIRED";

    static final String KIND_POS_TERMINAL = "POS_TERMINAL";

    @Id
    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "hardware_serial", nullable = false, updatable = false)
    private String hardwareSerial;

    @Column(name = "device_kind", nullable = false, updatable = false)
    private String deviceKind;

    @Column(name = "owner_entity_id", nullable = false, updatable = false)
    private UUID ownerEntityId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "current_till_position_id")
    private UUID currentTillPositionId;

    @Column(name = "enrolled_at", updatable = false)
    private Instant enrolledAt;

    // Written by the sync gateway's heartbeat (K-08) and the update service (doc 31), never here.
    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "status", nullable = false)
    private String status;

    @Transient
    private boolean isNew = true;

    protected Device() {}

    static Device enrol(
            UUID id,
            String hardwareSerial,
            String deviceKind,
            UUID ownerEntityId,
            UUID locationId,
            String appVersion,
            Instant enrolledAt) {
        Device device = new Device();
        device.id = id;
        device.hardwareSerial = hardwareSerial;
        device.deviceKind = deviceKind;
        device.ownerEntityId = ownerEntityId;
        device.locationId = locationId;
        device.appVersion = appVersion;
        device.enrolledAt = enrolledAt;
        device.status = STATUS_ENROLLED;
        return device;
    }

    void assignTo(UUID tillPositionId) {
        this.currentTillPositionId = tillPositionId;
        this.status = STATUS_ACTIVE;
    }

    /** A suspended device gives its position up to the replacement assigned there. */
    void givePositionUp() {
        this.currentTillPositionId = null;
    }

    void suspend() {
        this.status = STATUS_SUSPENDED;
    }

    void reinstate() {
        this.status = STATUS_ACTIVE;
    }

    void retire() {
        this.status = STATUS_RETIRED;
    }

    boolean isEnrolled() {
        return STATUS_ENROLLED.equals(status);
    }

    boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    boolean isSuspended() {
        return STATUS_SUSPENDED.equals(status);
    }

    boolean isRetired() {
        return STATUS_RETIRED.equals(status);
    }

    boolean isTill() {
        return KIND_POS_TERMINAL.equals(deviceKind);
    }

    String hardwareSerial() {
        return hardwareSerial;
    }

    String deviceKind() {
        return deviceKind;
    }

    UUID ownerEntityId() {
        return ownerEntityId;
    }

    UUID locationId() {
        return locationId;
    }

    UUID currentTillPositionId() {
        return currentTillPositionId;
    }

    String appVersion() {
        return appVersion;
    }

    String status() {
        return status;
    }

    Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("deviceId", id);
        state.put("hardwareSerial", hardwareSerial);
        state.put("deviceKind", deviceKind);
        state.put("ownerEntityId", ownerEntityId);
        state.put("locationId", locationId);
        state.put("tillPositionId", currentTillPositionId);
        state.put("appVersion", appVersion);
        state.put("status", status);
        return Collections.unmodifiableMap(state);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}
