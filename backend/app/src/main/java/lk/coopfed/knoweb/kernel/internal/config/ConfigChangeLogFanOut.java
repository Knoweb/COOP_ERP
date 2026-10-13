package lk.coopfed.knoweb.kernel.internal.config;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Change-log producer for till-visible configuration items (doc 32 section 5.2; K-08, K-11).
 *
 * <p>Listens to {@link ConfigChanged} events and appends {@code UPSERT} changes for the table
 * {@code "config"} to {@code kernel.change_log}:
 * <ul>
 *   <li>Location-scoped change: fans out to the exact shop target.
 *   <li>Entity-scoped change: fans out to all active shops of the entity via {@code party.change_log_shops}.
 *   <li>Federation-scoped change: fans out to all enrolled shops across the federation.
 *   <li>Changes to non-till-visible configuration keys are ignored.
 *   <li>Standard configuration changes are immediate ({@code applyFrom = null}) and non-urgent.
 * </ul>
 */
@Component
public class ConfigChangeLogFanOut {

    public static final String CONSUMER = "kernel.config.snapshot";

    private final ChangeLog changeLog;
    private final JdbcTemplate jdbc;

    public ConfigChangeLogFanOut(ChangeLog changeLog, JdbcTemplate jdbc) {
        this.changeLog = changeLog;
        this.jdbc = jdbc;
    }

    @EventConsumer(types = ConfigChanged.TYPE, consumer = CONSUMER)
    public void onConfigChanged(ConfigChanged event, ScopeContext scope) {
        if (!event.tillVisible()) {
            return;
        }

        UUID rowId = ConfigSnapshotContributor.rowId(event.key());
        List<Target> targets;

        if (event.scopeLocation() != null) {
            targets = List.of(new Target(event.scopeEntityId(), event.scopeLocation()));
        } else if (event.scopeEntityId() != null) {
            targets = jdbc.query(
                    "select shop_entity_id, shop_location_id from party.change_log_shops(?)",
                    (rs, rowNum) -> new Target(
                            rs.getObject("shop_entity_id", UUID.class), rs.getObject("shop_location_id", UUID.class)),
                    event.scopeEntityId());
        } else {
            targets = jdbc.query(
                    "select shop_entity_id, shop_location_id from party.change_log_shops(null)",
                    (rs, rowNum) -> new Target(
                            rs.getObject("shop_entity_id", UUID.class), rs.getObject("shop_location_id", UUID.class)));
        }

        if (targets.isEmpty()) {
            return;
        }

        changeLog.append(targets, List.of(Change.upsert(ConfigSnapshotContributor.TABLE, rowId)), null, false, scope);
    }
}
