package lk.coopfed.knoweb.m8reporting.internal.projection;

import java.sql.Timestamp;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ProjectionStateService of 28A section 4: per projection and entity, the last event applied
 * and when it happened. The dashboard shows it beside a figure; lag detection (LAGGING against
 * the archive head) and the rebuild status wait for the rebuild service (module README). The
 * latest event wins, so an old event replayed late does not move the state back.
 */
@Component
public class ProjectionStateStore {

    private final JdbcTemplate jdbc;

    ProjectionStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void advance(String name, String consumer, ProjectionEvent event, ScopeContext scope) {
        jdbc.update(
                """
                insert into reporting.projection_state
                       (name, owner_entity_id, consumer, last_event_id, last_event_type, last_event_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (name, owner_entity_id) do update
                   set last_event_id = excluded.last_event_id,
                       last_event_type = excluded.last_event_type,
                       last_event_at = excluded.last_event_at
                 where reporting.projection_state.last_event_at < excluded.last_event_at
                """,
                name,
                scope.entityId(),
                consumer,
                event.eventId(),
                event.type(),
                Timestamp.from(event.occurredAt()));
    }
}
