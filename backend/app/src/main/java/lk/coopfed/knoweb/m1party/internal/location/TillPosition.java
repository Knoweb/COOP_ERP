package lk.coopfed.knoweb.m1party.internal.location;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A till position: the logical lane of a shop that owns its own numbering series (doc 21
 * section 3.4; ADR-11: the series follow the position, not the device). A position belongs to
 * exactly one SHOP, its number is unique there, and it retires but is never deleted.
 */
@jakarta.persistence.Entity
@Table(schema = "party", name = "till_position")
public class TillPosition implements Persistable<UUID> {

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_RETIRED = "RETIRED";

    @Id
    @Column(name = "till_position_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "position_no", nullable = false, updatable = false)
    private short positionNo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "owner_entity_id", nullable = false, updatable = false)
    private UUID ownerEntityId;

    @Transient
    private boolean isNew = true;

    protected TillPosition() {}

    static TillPosition register(UUID id, Location location, int positionNo) {
        TillPosition position = new TillPosition();
        position.id = id;
        position.locationId = location.getId();
        position.ownerEntityId = location.ownerEntityId();
        position.positionNo = (short) positionNo;
        position.status = STATUS_ACTIVE;
        return position;
    }

    void retire() {
        this.status = STATUS_RETIRED;
    }

    boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    UUID locationId() {
        return locationId;
    }

    UUID ownerEntityId() {
        return ownerEntityId;
    }

    int positionNo() {
        return positionNo;
    }

    Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("tillPositionId", id);
        state.put("locationId", locationId);
        state.put("positionNo", (int) positionNo);
        state.put("status", status);
        state.put("ownerEntityId", ownerEntityId);
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
