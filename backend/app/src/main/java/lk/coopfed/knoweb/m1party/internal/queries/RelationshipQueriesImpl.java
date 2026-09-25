package lk.coopfed.knoweb.m1party.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipSide;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads party.entity_relationship under the caller's scope. Row-level security decides which
 * rows (own_read and party_read for the two sides, fed_view, ext_view); the conditions below
 * say which of those the question is about, never whose rows the caller may see.
 */
@Service
@Transactional(readOnly = true)
class RelationshipQueriesImpl implements RelationshipQueries {

    private static final String SELECT =
            """
            select relationship_id, seller_entity_id, buyer_entity_id, price_list_id, credit_limit,
                   payment_terms_days, discrepancy_window_days, order_lock_hours_before_eta,
                   allocation_rule, status, effective_from, effective_to
              from party.entity_relationship
            """;

    private final JdbcTemplate jdbc;

    RelationshipQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RelationshipView> lookupRelationship(
            UUID sellerEntityId, UUID buyerEntityId, LocalDate onDate, ScopeContext scope) {
        if (sellerEntityId == null || buyerEntityId == null || onDate == null || !hasScope(scope)) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        SELECT
                                + """
                                 where seller_entity_id = ?
                                   and buyer_entity_id = ?
                                   and status = 'ACTIVE'
                                   and effective_from <= ?
                                   and (effective_to is null or effective_to >= ?)
                                """,
                        RelationshipQueriesImpl::map,
                        sellerEntityId,
                        buyerEntityId,
                        onDate,
                        onDate)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RelationshipView> getRelationship(UUID relationshipId, ScopeContext scope) {
        if (relationshipId == null || !hasScope(scope)) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " where relationship_id = ?", RelationshipQueriesImpl::map, relationshipId).stream()
                .findFirst();
    }

    @Override
    public List<RelationshipView> listRelationships(RelationshipSide side, ScopeContext scope) {
        if (!hasScope(scope) || scope.entityId() == null) {
            return List.of();
        }
        UUID entity = scope.entityId();
        if (side == RelationshipSide.SELLER) {
            return jdbc.query(
                    SELECT + " where seller_entity_id = ? order by buyer_entity_id, effective_from",
                    RelationshipQueriesImpl::map,
                    entity);
        }
        if (side == RelationshipSide.BUYER) {
            return jdbc.query(
                    SELECT + " where buyer_entity_id = ? order by seller_entity_id, effective_from",
                    RelationshipQueriesImpl::map,
                    entity);
        }
        return jdbc.query(
                SELECT
                        + " where seller_entity_id = ? or buyer_entity_id = ?"
                        + " order by seller_entity_id, buyer_entity_id, effective_from",
                RelationshipQueriesImpl::map,
                entity,
                entity);
    }

    private static boolean hasScope(ScopeContext scope) {
        return scope != null && scope.hasActiveScope();
    }

    private static RelationshipView map(ResultSet rs, int row) throws SQLException {
        int paymentTerms = rs.getInt("payment_terms_days");
        Integer paymentTermsDays = rs.wasNull() ? null : paymentTerms;
        return new RelationshipView(
                rs.getObject("relationship_id", UUID.class),
                rs.getObject("seller_entity_id", UUID.class),
                rs.getObject("buyer_entity_id", UUID.class),
                rs.getObject("price_list_id", UUID.class),
                rs.getBigDecimal("credit_limit"),
                paymentTermsDays,
                rs.getInt("discrepancy_window_days"),
                rs.getInt("order_lock_hours_before_eta"),
                rs.getString("allocation_rule"),
                rs.getString("status"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class));
    }
}
