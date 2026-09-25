package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SyncStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SyncStatus} on the cursor and the last heartbeat, read under the caller's scope. */
@Component
public class JdbcSyncStatus implements SyncStatus {

    private final JdbcTemplate jdbc;

    JdbcSyncStatus(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceSyncState> state(UUID deviceId, ScopeContext ctx) {
        List<DeviceSyncState> found = jdbc.query(
                """
                select c.last_applied_seq, h.last_seen_at, coalesce(h.app_version, c.app_version) as app_version,
                       h.pending_event_count, h.snapshot_version
                  from kernel.device_sync_cursor c
                  left join kernel.device_heartbeat h on h.device_id = c.device_id
                 where c.device_id = ?
                """,
                (rs, n) -> {
                    Timestamp seen = rs.getTimestamp("last_seen_at");
                    return new DeviceSyncState(
                            deviceId,
                            seen == null ? null : seen.toInstant(),
                            rs.getString("app_version"),
                            rs.getLong("last_applied_seq"),
                            (Integer) rs.getObject("pending_event_count"),
                            (Long) rs.getObject("snapshot_version"));
                },
                deviceId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean drained(UUID deviceId, ScopeContext ctx) {
        Boolean drained = jdbc.queryForObject(
                """
                select exists (
                    select 1
                      from kernel.device_sync_cursor c
                      join kernel.device_heartbeat h on h.device_id = c.device_id
                     where c.device_id = ?
                       and c.in_flight_batch_id is null
                       and h.pending_event_count = 0
                       and coalesce(h.last_acknowledged_seq, 0) <= c.last_applied_seq)
                """,
                Boolean.class,
                deviceId);
        return Boolean.TRUE.equals(drained);
    }
}
