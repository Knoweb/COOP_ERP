package lk.coopfed.knoweb.m1party.internal.location;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A warehouse, shop or office of an entity (doc 21 section 3.3; table party.location). The owner
 * never changes (A-I2, a trigger backs it). The status follows doc 21 section 4.3:
 * PLANNED, ONBOARDING, ACTIVE, DORMANT; the handlers check which move is allowed, this class only
 * makes it.
 */
@jakarta.persistence.Entity
@Table(schema = "party", name = "location")
public class Location implements Persistable<UUID> {

    static final String TYPE_SHOP = "SHOP";

    static final String STATUS_PLANNED = "PLANNED";
    static final String STATUS_ONBOARDING = "ONBOARDING";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_DORMANT = "DORMANT";

    @Id
    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_entity_id", nullable = false, updatable = false)
    private UUID ownerEntityId;

    @Column(name = "location_code", nullable = false, updatable = false)
    private String locationCode;

    @Column(name = "location_type", nullable = false, updatable = false)
    private String locationType;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_si")
    private String nameSi;

    @Column(name = "name_ta")
    private String nameTa;

    @Column(name = "address")
    private String address;

    @Column(name = "district")
    private String district;

    @Column(name = "geo_lat")
    private BigDecimal geoLat;

    @Column(name = "geo_lng")
    private BigDecimal geoLng;

    @Column(name = "language")
    private String language;

    /** The JSON array of {@code TradingDay}; {@link TradingHours} reads and writes it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trading_hours")
    private String tradingHours;

    @Column(name = "size_band")
    private String sizeBand;

    @Column(name = "connectivity_spec_met", nullable = false)
    private boolean connectivitySpecMet;

    @Column(name = "primary_till_position_id")
    private UUID primaryTillPositionId;

    @Column(name = "status", nullable = false)
    private String status;

    @Transient
    private boolean isNew = true;

    protected Location() {}

    /** The facts a caller may change after registration (UpdateLocation). */
    record Facts(
            String nameEn,
            String nameSi,
            String nameTa,
            String address,
            String district,
            BigDecimal geoLat,
            BigDecimal geoLng,
            String language,
            String tradingHoursJson,
            String sizeBand) {}

    static Location register(UUID id, UUID ownerEntityId, String locationCode, String locationType, Facts facts) {
        Location location = new Location();
        location.id = id;
        location.ownerEntityId = ownerEntityId;
        location.locationCode = locationCode;
        location.locationType = locationType;
        location.status = STATUS_PLANNED;
        location.connectivitySpecMet = false;
        location.apply(facts);
        return location;
    }

    void update(Facts facts) {
        apply(facts);
    }

    private void apply(Facts facts) {
        this.nameEn = facts.nameEn().strip();
        this.nameSi = blankToNull(facts.nameSi());
        this.nameTa = blankToNull(facts.nameTa());
        this.address = blankToNull(facts.address());
        this.district = blankToNull(facts.district());
        this.geoLat = facts.geoLat();
        this.geoLng = facts.geoLng();
        this.language = facts.language();
        this.tradingHours = facts.tradingHoursJson();
        this.sizeBand = facts.sizeBand();
    }

    void confirmConnectivity() {
        this.connectivitySpecMet = true;
    }

    void moveTo(String newStatus) {
        this.status = newStatus;
    }

    void namePrimaryTill(UUID tillPositionId) {
        this.primaryTillPositionId = tillPositionId;
    }

    boolean isShop() {
        return TYPE_SHOP.equals(locationType);
    }

    boolean hasPrimaryTill() {
        return primaryTillPositionId != null;
    }

    UUID ownerEntityId() {
        return ownerEntityId;
    }

    String locationCode() {
        return locationCode;
    }

    String locationType() {
        return locationType;
    }

    String language() {
        return language;
    }

    String tradingHoursJson() {
        return tradingHours;
    }

    String sizeBand() {
        return sizeBand;
    }

    boolean connectivitySpecMet() {
        return connectivitySpecMet;
    }

    UUID primaryTillPositionId() {
        return primaryTillPositionId;
    }

    String status() {
        return status;
    }

    Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("locationId", id);
        state.put("ownerEntityId", ownerEntityId);
        state.put("locationCode", locationCode);
        state.put("locationType", locationType);
        state.put("nameEn", nameEn);
        state.put("nameSi", nameSi);
        state.put("nameTa", nameTa);
        state.put("address", address);
        state.put("district", district);
        state.put("geoLat", geoLat);
        state.put("geoLng", geoLng);
        state.put("language", language);
        state.put("tradingHours", tradingHours);
        state.put("sizeBand", sizeBand);
        state.put("connectivitySpecMet", connectivitySpecMet);
        state.put("primaryTillPositionId", primaryTillPositionId);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
