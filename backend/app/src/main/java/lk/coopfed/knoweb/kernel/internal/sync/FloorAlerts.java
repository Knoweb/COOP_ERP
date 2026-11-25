package lk.coopfed.knoweb.kernel.internal.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AppVersionFloor;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Doc 31 section 6 (FIX-14, K4-01): a till below the floor whose grace has expired "appears in
 * the helpdesk queue automatically", once. The ALERT ({@code SYNC_APP_VERSION_BELOW_FLOOR}) and
 * its {@code sync.anomaly.v1} are raised once per device and floor: the floor alerted on is kept
 * on the device's cursor ({@code floor_alerted}, kernel V0092), and only the statement that moves
 * it to a new floor writes the record. Evaluated on a batch that holds the cursor (not on one
 * refused with 409 or answered from a stored acknowledgement) and on every heartbeat, so a till
 * that has nothing to upload is alerted too. A later raise of the floor alerts again.
 */
@Component
public class FloorAlerts {

    static final String AUDIT_APP_VERSION_BELOW_FLOOR = "SYNC_APP_VERSION_BELOW_FLOOR";
    static final String ANOMALY_KIND = "APP_VERSION_BELOW_FLOOR";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    FloorAlerts(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Raises the ALERT when the device stands below the floor after its grace and has not been
     * alerted on this floor yet; joins the caller's transaction (the heartbeat's), or opens one.
     *
     * @return whether the ALERT was written now
     */
    @Transactional
    public boolean raiseOnce(ScopeContext device, String appVersion, AppVersionFloor.Standing floor) {
        if (!floor.afterGrace()) {
            return false;
        }
        // Rows that do not match the condition are not locked: once the floor is recorded, this
        // statement touches nothing and cannot hold up the next batch's claim (FOR UPDATE NOWAIT).
        int moved = jdbc.update(
                """
                update kernel.device_sync_cursor set floor_alerted = ?
                 where device_id = ? and floor_alerted is distinct from ?
                """,
                floor.floor(),
                device.deviceId(),
                floor.floor());
        if (moved == 0) {
            return false;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("appVersion", appVersion);
        after.put("floor", floor.floor());
        after.put(
                "graceEndedAt",
                floor.graceEndsAt() == null ? null : floor.graceEndsAt().toString());
        audit.record(
                AUDIT_APP_VERSION_BELOW_FLOOR,
                Subject.of("device", device.deviceId()),
                null,
                after,
                device,
                "Till application below version floor after grace expired");
        events.publish(new SyncAnomaly(Ids.next(), device.deviceId(), ANOMALY_KIND, null, null));
        return true;
    }
}
