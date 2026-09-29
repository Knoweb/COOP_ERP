package lk.coopfed.knoweb.m4trading.internal.transfer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.api.TransferRequestLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The reads of a transfer request and the society's decision on it, under the caller's row-level security. */
@Component
public class TransferRequestReads {

    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private final JdbcTemplate jdbc;

    TransferRequestReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Request> request(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select r.request_id, r.owner_entity_id, r.location_id,
                       case when d.decision = 'APPROVED' then d.location_id else r.from_location_id end as from_location_id, r.reason, r.requested_by,
                       r.requested_at, d.decision, d.reason as decision_reason, d.decided_by, d.decided_at
                  from trading.transfer_request r
                  left join trading.transfer_request_decision d on d.request_id = r.request_id
                 where r.request_id = ?
                """,
                requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        List<TransferRequestLine> lines = new ArrayList<>();
        for (Map<String, Object> line : jdbc.queryForList(
                "select line_id, sku_id, qty from trading.transfer_request_line where request_id = ? order by line_no",
                requestId)) {
            lines.add(new TransferRequestLine(
                    (UUID) line.get("line_id"), (UUID) line.get("sku_id"), (BigDecimal) line.get("qty")));
        }
        return Optional.of(new Request(
                requestId,
                (UUID) row.get("owner_entity_id"),
                (UUID) row.get("from_location_id"),
                (UUID) row.get("location_id"),
                (String) row.get("reason"),
                (UUID) row.get("requested_by"),
                instant(row.get("requested_at")),
                row.get("decision") == null ? REQUESTED : (String) row.get("decision"),
                (String) row.get("decision_reason"),
                (UUID) row.get("decided_by"),
                instant(row.get("decided_at")),
                List.copyOf(lines)));
    }

    /** The requests the caller's scope reads (a shop its own; the society all), newest first. */
    public List<UUID> requestIds() {
        return jdbc.queryForList(
                "select request_id from trading.transfer_request order by requested_at desc, request_id desc",
                UUID.class);
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
    }

    public record Request(
            UUID requestId,
            UUID ownerEntityId,
            UUID fromLocationId,
            UUID toLocationId,
            String reason,
            UUID requestedBy,
            Instant requestedAt,
            String status,
            String decisionReason,
            UUID decidedBy,
            Instant decidedAt,
            List<TransferRequestLine> lines) {}
}
