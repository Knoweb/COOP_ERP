package lk.coopfed.knoweb.m5inventory.internal.review;

import java.math.BigDecimal;
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

/**
 * Read-only store for discovering unacknowledged negative stock lots (doc 25 section 3.2; 25A section 7.2; ticket M5-06b / INV-04).
 *
 * <p>Every query method is {@code @Transactional(readOnly = true)} so that the kernel's
 * {@code ScopeConnectionCustomizer} aspect fires and applies the RLS session variables ({@code
 * kernel.scope_class()}, {@code kernel.scope_entity()}, etc.) before any query runs.
 */
@Component
@Transactional(readOnly = true)
public class NegativeLotReviewStore {

    public record CandidateLot(
            UUID stockLotId,
            UUID ownerEntityId,
            UUID locationId,
            UUID batchId,
            UUID skuId,
            String condition,
            BigDecimal qtyOnHand,
            Instant negativeSince,
            Instant acknowledgedAt) {}

    private static final String SELECT_NEGATIVE_CANDIDATES =
            """
            select stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, condition,
                   qty_on_hand, negative_since, negative_acknowledged_at
              from inventory.stock_lot
             where qty_on_hand < 0
               and negative_since is not null
               and (negative_acknowledged_at is null or negative_acknowledged_at < negative_since)
            """;

    private final JdbcTemplate jdbc;

    public NegativeLotReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Discover all candidate negative lots across the fleet (typically under {@code federationView()}).
     *
     * @param scope caller's scope
     * @return list of currently unacknowledged negative lots
     */
    public List<CandidateLot> findCandidateLots(ScopeContext scope) {
        return jdbc.query(
                SELECT_NEGATIVE_CANDIDATES + " order by owner_entity_id, location_id, stock_lot_id",
                NegativeLotReviewStore::mapCandidate);
    }

    /**
     * Discover candidate negative lots at a specific location under the caller's scope.
     *
     * @param locationId location to check
     * @param scope caller's scope
     * @return list of currently unacknowledged negative lots at the location
     */
    public List<CandidateLot> findCandidateLots(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                SELECT_NEGATIVE_CANDIDATES + " and location_id = ? order by stock_lot_id",
                NegativeLotReviewStore::mapCandidate,
                locationId);
    }

    /**
     * Re-query a single lot by id under an entity's OWN scope to verify its current state
     * before escalating (guards against stale candidates).
     *
     * @param stockLotId stock lot ID
     * @param scope caller's OWN scope
     * @return candidate lot if still negative and unacknowledged
     */
    public Optional<CandidateLot> findLotById(UUID stockLotId, ScopeContext scope) {
        return jdbc
                .query(
                        SELECT_NEGATIVE_CANDIDATES + " and stock_lot_id = ?",
                        NegativeLotReviewStore::mapCandidate,
                        stockLotId)
                .stream()
                .findFirst();
    }

    private static CandidateLot mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        Instant since = instant(rs, "negative_since");
        Instant acknowledged = instant(rs, "negative_acknowledged_at");
        return new CandidateLot(
                rs.getObject("stock_lot_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getString("condition"),
                rs.getBigDecimal("qty_on_hand"),
                since,
                acknowledged);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
