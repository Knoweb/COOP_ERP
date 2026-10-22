package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only discovery of dispatched transfers that do not yet have a receipt fact. */
@Component
@Transactional(readOnly = true)
public class TransferOverdueStore {

    public record CandidateTransfer(
            UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, Instant dispatchedAt) {}

    private static final String SELECT_UNRECEIVED =
            """
            select d.transfer_id, d.owner_entity_id, d.location_id, d.to_location_id, d.dispatched_at
              from inventory.transfer_dispatch d
             where not exists (
                       select 1
                         from inventory.transfer_receipt r
                        where r.transfer_id = d.transfer_id
                   )
            """;

    private final JdbcTemplate jdbc;

    public TransferOverdueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CandidateTransfer> findCandidates(ScopeContext scope) {
        return jdbc.query(
                SELECT_UNRECEIVED + " order by d.owner_entity_id, d.dispatched_at, d.transfer_id",
                TransferOverdueStore::mapCandidate);
    }

    public Optional<CandidateTransfer> findById(UUID transferId, ScopeContext scope) {
        return jdbc
                .query(SELECT_UNRECEIVED + " and d.transfer_id = ?", TransferOverdueStore::mapCandidate, transferId)
                .stream()
                .findFirst();
    }

    private static CandidateTransfer mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateTransfer(
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("to_location_id", UUID.class),
                rs.getObject("dispatched_at", OffsetDateTime.class).toInstant());
    }
}
