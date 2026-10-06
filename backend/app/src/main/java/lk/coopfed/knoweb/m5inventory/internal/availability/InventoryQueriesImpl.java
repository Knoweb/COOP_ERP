package lk.coopfed.knoweb.m5inventory.internal.availability;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
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
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.query.Availability;
import lk.coopfed.knoweb.m5inventory.query.EntityCost;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.MovementView;
import lk.coopfed.knoweb.m5inventory.query.OpeningBalanceView;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
import lk.coopfed.knoweb.m5inventory.query.StockCardLine;
import lk.coopfed.knoweb.m5inventory.query.TransferView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of 25A section 7 (AvailabilityService and FefoService of section 4, as queries).
 * Read-only transactions that take the scope: the kernel puts it on the transaction and row-level
 * security filters; no query names an owner. The FEFO rank ranks the GOOD lots with stock at a
 * location for a SKU by expiry, none last, then by when they were received (25A section 7,
 * "FefoService"); the lot's id breaks a tie so the order is the same on every call.
 *
 * <p><b>Expired stock</b> (wave 2, M5-01; {@code docs/progress/deviations/2026-10-06-wave2-expired-stock.md}):
 * a lot whose expiry date is before the business date ({@link BusinessDay}, passed into every query
 * as a parameter, never SQL {@code current_date}) has no rank, is never available, never picked and
 * never a price candidate; {@link #balances} still shows it, flagged {@code expired}, so the stock
 * book shows what has to be written off. A lot expiring today is still sellable today.
 */
@Service
@Transactional(readOnly = true)
class InventoryQueriesImpl implements InventoryQueries {

    /**
     * A lot with its FEFO rank among the sellable lots of its location and SKU, whether it is
     * expired, and what the open pick lists reserve of it. The first parameter is the business date.
     */
    private static final String RANKED_LOTS =
            """
            select l.stock_lot_id, l.owner_entity_id, l.location_id, l.sku_id, l.batch_id, l.expiry_date,
                   l.condition, l.qty_on_hand, l.unit_cost, l.received_at, l.negative_since,
                   coalesce(l.expiry_date < d.today, false) as expired,
                   coalesce((select sum(p.qty)
                               from inventory.pick_list_line p
                               join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                              where pl.status = 'OPEN' and p.stock_lot_id = l.stock_lot_id), 0) as reserved,
                   case when l.condition = 'GOOD' and l.qty_on_hand > 0
                             and (l.expiry_date is null or l.expiry_date >= d.today)
                        then row_number() over (
                                 partition by l.location_id, l.sku_id,
                                              (l.condition = 'GOOD' and l.qty_on_hand > 0
                                               and (l.expiry_date is null or l.expiry_date >= d.today))
                                 order by l.expiry_date nulls last, l.received_at, l.stock_lot_id)
                   end as fefo_rank
              from inventory.stock_lot l
             cross join (select ?::date as today) d
            """;

    private final JdbcTemplate jdbc;
    private final BusinessDay businessDay;

    InventoryQueriesImpl(JdbcTemplate jdbc, BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.businessDay = businessDay;
    }

    @Override
    public List<LotBalance> balances(UUID locationId, UUID skuId, boolean includeZero, ScopeContext scope) {
        return jdbc.query(
                "select * from (" + RANKED_LOTS + " where l.location_id = ?) ranked"
                        + " where (?::uuid is null or sku_id = ?) and (? or qty_on_hand <> 0)"
                        + " order by fefo_rank is null, sku_id, fefo_rank, condition, expiry_date nulls last, stock_lot_id",
                InventoryQueriesImpl::lot,
                today(),
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
        Date today = today();
        return jdbc.query(
                connection -> {
                    // The open picks reserve units of lots that are still in date: a pick of a lot
                    // that expired since is not taken twice off what remains.
                    PreparedStatement ps = connection.prepareStatement(
                            """
                            select loc.id as location_id, sku.id as sku_id,
                                   coalesce(sum(l.qty_on_hand), 0)
                                   - coalesce((select sum(p.qty)
                                                 from inventory.pick_list_line p
                                                 join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                                                 join inventory.stock_lot r on r.stock_lot_id = p.stock_lot_id
                                                where pl.status = 'OPEN'
                                                  and p.location_id = loc.id and p.sku_id = sku.id
                                                  and (r.expiry_date is null or r.expiry_date >= ?)), 0) as on_hand
                              from unnest(?::uuid[]) as loc (id)
                             cross join unnest(?::uuid[]) as sku (id)
                              left join inventory.stock_lot l
                                on l.location_id = loc.id and l.sku_id = sku.id
                               and l.condition = 'GOOD' and l.qty_on_hand > 0
                               and (l.expiry_date is null or l.expiry_date >= ?)
                             group by loc.id, sku.id
                             order by loc.id, sku.id
                            """);
                    ps.setDate(1, today);
                    ps.setArray(2, uuids(connection, locationIds));
                    ps.setArray(3, uuids(connection, skuIds));
                    ps.setDate(4, today);
                    return ps;
                },
                (rs, n) -> new Availability(
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        nonNegative(rs.getBigDecimal("on_hand"))));
    }

    /**
     * The in-date GOOD lots with stock in FEFO order, each with what is free of it: its quantity
     * less what the open pick lists reserve (wave 2, observation of the M5 fix review: a transfer
     * must not take units a delivery note already holds). A lot with nothing free is left out.
     */
    @Override
    public List<LotBalance> pickBatches(UUID locationId, UUID skuId, ScopeContext scope) {
        return jdbc.query(
                "select * from (" + RANKED_LOTS + " where l.location_id = ? and l.sku_id = ?) ranked"
                        + " where fefo_rank is not null and qty_on_hand - reserved > 0 order by fefo_rank",
                (rs, n) -> withQty(lot(rs, n), rs.getBigDecimal("qty_on_hand").subtract(rs.getBigDecimal("reserved"))),
                today(),
                locationId,
                skuId);
    }

    @Override
    public List<LotBalance> inStockBatches(Collection<UUID> locationIds, UUID skuId, ScopeContext scope) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        Date today = today();
        return jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement("select * from (" + RANKED_LOTS
                            + " where l.location_id = any (?::uuid[]) and l.sku_id = ?) ranked"
                            + " where fefo_rank is not null order by location_id, fefo_rank");
                    ps.setDate(1, today);
                    ps.setArray(2, uuids(connection, locationIds));
                    ps.setObject(3, skuId);
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
    public List<StockCardLine> stockCard(UUID locationId, UUID skuId, ScopeContext scope) {
        return jdbc.query(
                """
                select movement_id, location_id, sku_id, batch_id, condition, movement_type, qty_delta,
                       unit_cost_at_movement, document_id, document_line_id, source, movement_seq, occurred_at,
                       sum(qty_delta) over (order by received_at, source, movement_seq, movement_id
                                            rows between unbounded preceding and current row) as balance_after
                  from inventory.stock_movement
                 where location_id = ? and sku_id = ?
                 order by received_at, source, movement_seq, movement_id
                """,
                (rs, n) -> new StockCardLine(
                        new MovementView(
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
                                rs.getObject("occurred_at", OffsetDateTime.class)
                                        .toInstant()),
                        rs.getBigDecimal("balance_after")),
                locationId,
                skuId);
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

    @Override
    public Optional<TransferView> transfer(UUID transferId, ScopeContext scope) {
        return transfersWhere("t.transfer_id = ?", transferId).stream().findFirst();
    }

    @Override
    public Optional<TransferView> transferOfRequest(UUID transferRequestId, ScopeContext scope) {
        if (transferRequestId == null) {
            return Optional.empty();
        }
        return transfersWhere("t.transfer_request_id = ?", transferRequestId).stream()
                .findFirst();
    }

    @Override
    public List<TransferView> transfers(UUID locationId, ScopeContext scope) {
        return transfersWhere("(t.location_id = ? or t.to_location_id = ?)", locationId, locationId);
    }

    /**
     * Transfers with their receipt, if any: RECEIVED when the destination wrote its receipt row,
     * IN_TRANSIT until then (the two halves are two rows; V0004).
     */
    private List<TransferView> transfersWhere(String condition, Object... args) {
        return jdbc.query(
                """
                select t.transfer_id, t.location_id, t.to_location_id, t.issued_by, t.issued_at,
                       r.received_by, r.received_at, r.transfer_id is not null as received
                  from inventory.transfer t
                  left join inventory.transfer_receipt r on r.transfer_id = t.transfer_id
                 where """
                        + " " + condition
                        + " order by t.issued_at desc, t.transfer_id",
                (rs, n) -> {
                    UUID id = rs.getObject("transfer_id", UUID.class);
                    OffsetDateTime issued = rs.getObject("issued_at", OffsetDateTime.class);
                    OffsetDateTime received = rs.getObject("received_at", OffsetDateTime.class);
                    return new TransferView(
                            id,
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("to_location_id", UUID.class),
                            rs.getBoolean("received") ? "RECEIVED" : "IN_TRANSIT",
                            rs.getObject("issued_by", UUID.class),
                            issued == null ? null : issued.toInstant(),
                            rs.getObject("received_by", UUID.class),
                            received == null ? null : received.toInstant(),
                            transferLines(id));
                },
                args);
    }

    private List<TransferView.Line> transferLines(UUID transferId) {
        return jdbc.query(
                """
                select line_no, batch_id, sku_id, qty, unit_cost
                  from inventory.transfer_line
                 where transfer_id = ?
                 order by line_no
                """,
                (rs, n) -> new TransferView.Line(
                        rs.getInt("line_no"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("unit_cost")),
                transferId);
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
                rs.getObject("negative_since") != null,
                rs.getBoolean("expired"));
    }

    private static LotBalance withQty(LotBalance lot, BigDecimal qty) {
        return new LotBalance(
                lot.stockLotId(),
                lot.ownerEntityId(),
                lot.locationId(),
                lot.skuId(),
                lot.batchId(),
                lot.expiryDate(),
                lot.condition(),
                qty,
                lot.fefoRank(),
                lot.unitCost(),
                lot.receivedAt(),
                lot.negative(),
                lot.expired());
    }

    private Date today() {
        return Date.valueOf(businessDay.today());
    }
}
