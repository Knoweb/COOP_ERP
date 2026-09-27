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
import lk.coopfed.knoweb.m5inventory.query.MovementView;
import lk.coopfed.knoweb.m5inventory.query.OpeningBalanceView;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
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
                                   coalesce(sum(l.qty_on_hand), 0)
                                   - coalesce((select sum(p.qty)
                                                 from inventory.pick_list_line p
                                                 join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                                                where pl.status = 'OPEN'
                                                  and p.location_id = loc.id and p.sku_id = sku.id), 0) as on_hand
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

    @Override
    public List<MovementView> movementsOf(UUID documentId, ScopeContext scope) {
        return jdbc.query(
                """
                select movement_id, location_id, sku_id, batch_id, condition, movement_type, qty_delta,
                       unit_cost_at_movement, document_id, document_line_id, source, movement_seq, occurred_at
                  from inventory.stock_movement
                 where document_id = ?
                 order by received_at, location_id, source, movement_seq
                """,
                (rs, n) -> new MovementView(
                        rs.getObject("movement_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("condition"),
                        rs.getString("movement_type"),
                        rs.getBigDecimal("qty_delta"),
                        rs.getBigDecimal("unit_cost_at_movement"),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_line_id", UUID.class),
                        rs.getString("source"),
                        rs.getLong("movement_seq"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()),
                documentId);
    }

    @Override
    public Optional<PickListView> pickList(UUID deliveryDocumentId, ScopeContext scope) {
        return jdbc
                .query(
                        "select pick_list_id, status from inventory.pick_list where delivery_document_id = ?",
                        (rs, n) -> new PickHead(rs.getObject("pick_list_id", UUID.class), rs.getString("status")),
                        deliveryDocumentId)
                .stream()
                .findFirst()
                .map(head -> new PickListView(
                        head.pickListId(),
                        deliveryDocumentId,
                        head.status(),
                        jdbc.query(
                                """
                                select delivery_line_id, sku_id, location_id, stock_lot_id, batch_id, qty
                                  from inventory.pick_list_line
                                 where pick_list_id = ?
                                 order by delivery_line_id, stock_lot_id is null, location_id
                                """,
                                (rs, n) -> new PickListView.Pick(
                                        rs.getObject("delivery_line_id", UUID.class),
                                        rs.getObject("sku_id", UUID.class),
                                        rs.getObject("location_id", UUID.class),
                                        rs.getObject("stock_lot_id", UUID.class),
                                        rs.getObject("batch_id", UUID.class),
                                        rs.getBigDecimal("qty")),
                                head.pickListId())));
    }

    private record PickHead(UUID pickListId, String status) {}

    @Override
    public Optional<OpeningBalanceView> openingBalance(UUID openingBalanceId, ScopeContext scope) {
        List<OpeningBalanceView.Line> lines = jdbc.query(
                """
                select line_no, batch_id, sku_id, condition, qty, unit_cost
                  from inventory.opening_balance_line
                 where opening_balance_id = ?
                 order by line_no
                """,
                (rs, n) -> new OpeningBalanceView.Line(
                        rs.getInt("line_no"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("unit_cost")),
                openingBalanceId);
        return jdbc
                .query(
                        """
                        select opening_balance_id, location_id, status, prepared_by, signed_entity_by,
                               countersigned_by, document_id
                          from inventory.opening_balance
                         where opening_balance_id = ?
                        """,
                        (rs, n) -> new OpeningBalanceView(
                                rs.getObject("opening_balance_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("status"),
                                rs.getObject("prepared_by", UUID.class),
                                rs.getObject("signed_entity_by", UUID.class),
                                rs.getObject("countersigned_by", UUID.class),
                                rs.getObject("document_id", UUID.class),
                                lines),
                        openingBalanceId)
                .stream()
                .findFirst();
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
