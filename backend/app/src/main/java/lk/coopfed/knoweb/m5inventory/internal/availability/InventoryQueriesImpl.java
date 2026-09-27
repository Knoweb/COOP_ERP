package lk.coopfed.knoweb.m5inventory.internal.availability;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.query.Availability;
import lk.coopfed.knoweb.m5inventory.query.EntityCost;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of 25A section 7 (AvailabilityService and FefoService of section 4, as queries).
 * Read-only transactions that take the scope: the kernel puts it on the transaction and row-level
 * security filters; no query names an owner. The FEFO rank ranks the GOOD lots with stock at a
 * location for a SKU by expiry, none last, then by when they were received (25A section 7,
 * "FefoService"); the lot's id breaks a tie so the order is the same on every call.
 */
@Service
@Transactional(readOnly = true)
class InventoryQueriesImpl implements InventoryQueries {

    /** A lot with its FEFO rank among the sellable lots of its location and SKU. */
    private static final String RANKED_LOTS =
            """
            select l.stock_lot_id, l.owner_entity_id, l.location_id, l.sku_id, l.batch_id, l.expiry_date,
                   l.condition, l.qty_on_hand, l.unit_cost, l.received_at, l.negative_since,
                   case when l.condition = 'GOOD' and l.qty_on_hand > 0
                        then row_number() over (
                                 partition by l.location_id, l.sku_id, (l.condition = 'GOOD' and l.qty_on_hand > 0)
                                 order by l.expiry_date nulls last, l.received_at, l.stock_lot_id)
                   end as fefo_rank
              from inventory.stock_lot l
            """;

    private final JdbcTemplate jdbc;

    InventoryQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LotBalance> balances(UUID locationId, UUID skuId, boolean includeZero, ScopeContext scope) {
        return jdbc.query(
                "select * from (" + RANKED_LOTS + " where l.location_id = ?) ranked"
                        + " where (?::uuid is null or sku_id = ?) and (? or qty_on_hand <> 0)"
                        + " order by fefo_rank is null, sku_id, fefo_rank, condition, expiry_date nulls last, stock_lot_id",
                InventoryQueriesImpl::lot,
                locationId,
                skuId,
                skuId,
                includeZero);
    }

    @Override
    public List<Availability> availability(Collection<UUID> locationIds, Collection<UUID> skuIds, ScopeContext scope) {
        if (locationIds.isEmpty() || skuIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            """
                            select loc.id as location_id, sku.id as sku_id,
                                   coalesce(sum(l.qty_on_hand), 0) as on_hand
                              from unnest(?::uuid[]) as loc (id)
                             cross join unnest(?::uuid[]) as sku (id)
                              left join inventory.stock_lot l
                                on l.location_id = loc.id and l.sku_id = sku.id
                               and l.condition = 'GOOD' and l.qty_on_hand > 0
                             group by loc.id, sku.id
                             order by loc.id, sku.id
                            """);
                    ps.setArray(1, uuids(connection, locationIds));
                    ps.setArray(2, uuids(connection, skuIds));
                    return ps;
                },
                (rs, n) -> new Availability(
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        nonNegative(rs.getBigDecimal("on_hand"))));
    }

    @Override
    public List<LotBalance> pickBatches(UUID locationId, UUID skuId, ScopeContext scope) {
        return jdbc.query(
                "select * from (" + RANKED_LOTS + " where l.location_id = ? and l.sku_id = ?) ranked"
                        + " where fefo_rank is not null order by fefo_rank",
                InventoryQueriesImpl::lot,
                locationId,
                skuId);
    }

    @Override
    public List<LotBalance> inStockBatches(Collection<UUID> locationIds, UUID skuId, ScopeContext scope) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement("select * from (" + RANKED_LOTS
                            + " where l.location_id = any (?::uuid[]) and l.sku_id = ?) ranked"
                            + " where fefo_rank is not null order by location_id, fefo_rank");
                    ps.setArray(1, uuids(connection, locationIds));
                    ps.setObject(2, skuId);
                    return ps;
                },
                InventoryQueriesImpl::lot);
    }

    @Override
    public List<UUID> skusWithLots(UUID locationId, ScopeContext scope) {
        return jdbc.queryForList(
                "select distinct sku_id from inventory.stock_lot where location_id = ? and qty_on_hand <> 0"
                        + " order by sku_id",
                UUID.class,
                locationId);
    }

    @Override
    public Optional<EntityCost> entityAverageCost(UUID skuId, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "select owner_entity_id, sku_id, qty_on_hand, avg_cost from inventory.entity_sku_cost"
                                + " where owner_entity_id = ? and sku_id = ?",
                        (rs, n) -> new EntityCost(
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("sku_id", UUID.class),
                                rs.getBigDecimal("qty_on_hand"),
                                rs.getBigDecimal("avg_cost")),
                        scope.entityId(),
                        skuId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean lotsConsumed(UUID grnDocumentId, ScopeContext scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (
                    select 1
                      from inventory.stock_movement r
                      join inventory.stock_movement m
                        on m.location_id = r.location_id and m.batch_id = r.batch_id and m.condition = r.condition
                     where r.document_id = ? and r.movement_type = 'RECEIPT'
                       and m.document_id <> r.document_id
                       and m.movement_type <> 'GRN_REVERSAL'
                       and m.received_at >= r.received_at)
                """,
                Boolean.class,
                grnDocumentId));
    }

    private static Array uuids(java.sql.Connection connection, Collection<UUID> ids) throws SQLException {
        return connection.createArrayOf("uuid", ids.toArray());
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO.setScale(3) : value;
    }

    private static LotBalance lot(ResultSet rs, int n) throws SQLException {
        OffsetDateTime received = rs.getObject("received_at", OffsetDateTime.class);
        long rank = rs.getLong("fefo_rank");
        boolean ranked = !rs.wasNull();
        return new LotBalance(
                rs.getObject("stock_lot_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getString("condition"),
                rs.getBigDecimal("qty_on_hand"),
                ranked ? (int) rank : null,
                rs.getBigDecimal("unit_cost"),
                received == null ? null : received.toInstant(),
                rs.getObject("negative_since") != null);
    }
}
