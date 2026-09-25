package lk.coopfed.knoweb.kernel.internal.sync;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The devices of M1 as the sync gateway needs them: status, entity, till position and location
 * (M1's {@code party.device}, {@code party.till_position}, {@code party.location}, as
 * {@code m1party/V0001}, {@code V0005} and {@code V0009} define them). Read as the federation-wide viewer, because the device's
 * scope is what is being resolved; never written: the devices are M1's (21A section 6).
 *
 * <p>Cached per instance, as 19A section 2 asks ("DeviceAuth validates status ACTIVE against M1
 * through the permission cache"), and emptied by M1's device events
 * ({@link DeviceCacheInvalidator}); the expiry bounds how long an instance that missed an event
 * serves a stale status. A suspension therefore bites at the next call on the instance that
 * received the event, and within the expiry everywhere.
 */
@Component
class DeviceDirectory {

    static final Duration TTL = Duration.ofSeconds(60);

    /**
     * One device.
     *
     * @param status         ENROLLED, ACTIVE, SUSPENDED or RETIRED (M1)
     * @param tillPositionId the position it is assigned to; null when unassigned
     * @param locationId     the position's shop; the device's own location (M1-06) when unassigned
     * @param primaryTill    the position is its shop's primary till (holds the location series)
     */
    record DeviceRecord(
            UUID deviceId,
            UUID ownerEntityId,
            String status,
            String hardwareSerial,
            UUID tillPositionId,
            UUID locationId,
            Integer positionNo,
            boolean primaryTill) {

        boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final Cache<UUID, Optional<DeviceRecord>> cache =
            Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build();

    DeviceDirectory(JdbcTemplate jdbc, SystemScope system) {
        this.jdbc = jdbc;
        this.system = system;
    }

    Optional<DeviceRecord> find(UUID deviceId) {
        return cache.get(deviceId, this::load);
    }

    /** Bypasses the cache: the enrolment decides on the state of now. */
    Optional<DeviceRecord> findFresh(UUID deviceId) {
        Optional<DeviceRecord> found = load(deviceId);
        cache.put(deviceId, found);
        return found;
    }

    void invalidate(UUID deviceId) {
        cache.invalidate(deviceId);
    }

    void invalidateAll() {
        cache.invalidateAll();
    }

    private Optional<DeviceRecord> load(UUID deviceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The read joins the caller's transaction and would put the federation view on its
            // connection for the rest of it: whatever the caller wrote next would run as nobody.
            throw new IllegalStateException("DeviceDirectory reads outside a transaction; look the device up first");
        }
        List<DeviceRecord> found = system.inScope(
                SystemScope.federationView(),
                () -> jdbc.query(
                        """
                        select d.device_id, d.owner_entity_id, d.status, d.hardware_serial,
                               d.current_till_position_id, coalesce(p.location_id, d.location_id) as location_id, p.position_no,
                               coalesce(l.primary_till_position_id = d.current_till_position_id, false) as primary_till
                          from party.device d
                          left join party.till_position p on p.till_position_id = d.current_till_position_id
                          left join party.location l on l.location_id = p.location_id
                         where d.device_id = ?
                        """,
                        (rs, n) -> new DeviceRecord(
                                rs.getObject("device_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("hardware_serial"),
                                rs.getObject("current_till_position_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                (Integer) rs.getObject("position_no", Integer.class),
                                rs.getBoolean("primary_till")),
                        deviceId));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }
}
