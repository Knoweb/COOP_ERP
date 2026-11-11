package lk.coopfed.knoweb.m9integration.internal.journal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only discovery of generated exports awaiting acknowledgement. */
@Component
@Transactional(readOnly = true)
public class JournalExportOverdueStore {

    public record Candidate(UUID exportId, UUID ownerEntityId, Instant generatedAt) {}

    private final JdbcTemplate jdbc;

    public JournalExportOverdueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> findCandidates(ScopeContext scope) {
        return jdbc.query(
                """
                select export_id, owner_entity_id, generated_at
                  from integration.journal_export
                 where status = 'GENERATED'
                 order by owner_entity_id, generated_at, export_id
                """,
                (rs, i) -> new Candidate(
                        rs.getObject("export_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getTimestamp("generated_at").toInstant()));
    }

    public Optional<Candidate> findByIdForUpdate(UUID exportId, ScopeContext scope) {
        return jdbc
                .query(
                        """
                        select export_id, owner_entity_id, generated_at
                          from integration.journal_export
                         where export_id = ?
                           and status = 'GENERATED'
                         for update
                        """,
                        (rs, i) -> new Candidate(
                                rs.getObject("export_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getTimestamp("generated_at").toInstant()),
                        exportId)
                .stream()
                .findFirst();
    }
}
