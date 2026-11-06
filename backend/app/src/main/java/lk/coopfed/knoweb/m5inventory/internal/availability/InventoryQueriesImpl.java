package lk.coopfed.knoweb.m5inventory.internal.availability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.DepartmentCategoryView;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.internal.expiry.ExpiryAlertStore;
import lk.coopfed.knoweb.m5inventory.internal.reorder.ReorderStore;
import lk.coopfed.knoweb.m5inventory.internal.reprice.RepriceStore;
import lk.coopfed.knoweb.m5inventory.query.Availability;
import lk.coopfed.knoweb.m5inventory.query.EntityCost;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LocationSalesCost;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.MovementView;
import lk.coopfed.knoweb.m5inventory.query.NearExpiryLotView;
import lk.coopfed.knoweb.m5inventory.query.OpeningBalanceView;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
import lk.coopfed.knoweb.m5inventory.query.PickReplacementCandidate;
import lk.coopfed.knoweb.m5inventory.query.ReorderControlView;
import lk.coopfed.knoweb.m5inventory.query.SlowMovingStockView;
import lk.coopfed.knoweb.m5inventory.query.StockCardLine;
import lk.coopfed.knoweb.m5inventory.query.StockExplanationView;
import lk.coopfed.knoweb.m5inventory.query.StockRepricingView;
import lk.coopfed.knoweb.m5inventory.query.StockSummaryView;
import lk.coopfed.knoweb.m5inventory.query.TransferView;
import org.springframework.beans.factory.annotation.Value;
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
                               left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                              where pl.status = 'OPEN'
                                and coalesce(o.replacement_stock_lot_id, p.stock_lot_id)
                                    = l.stock_lot_id), 0) as reserved,
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
    private final ControlPolicy policy;
    private final ZoneId zone;
    private final ReorderStore reorderStore;
    private final RepriceStore repriceStore;
    private final ExpiryAlertStore expiryAlertStore;
    private final CatalogueQueries catalogueQueries;
    private final PartyQueries partyQueries;

    InventoryQueriesImpl(
            JdbcTemplate jdbc,
            BusinessDay businessDay,
            ControlPolicy policy,
            ReorderStore reorderStore,
            RepriceStore repriceStore,
            ExpiryAlertStore expiryAlertStore,
            CatalogueQueries catalogueQueries,
            PartyQueries partyQueries,
            @Value("${coop-erp.business-timezone:Asia/Colombo}") String zone) {
        this.jdbc = jdbc;
        this.businessDay = businessDay;
        this.policy = policy;
        this.zone = ZoneId.of(zone);
        this.reorderStore = reorderStore;
        this.repriceStore = repriceStore;
        this.expiryAlertStore = expiryAlertStore;
        this.catalogueQueries = catalogueQueries;
        this.partyQueries = partyQueries;
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
                                                  left join inventory.pick_override o
                                                    on o.pick_line_id = p.pick_line_id
                                                 join inventory.stock_lot r
                                                   on r.stock_lot_id = coalesce(
                                                       o.replacement_stock_lot_id, p.stock_lot_id)
                                                where pl.status = 'OPEN'
                                                  and coalesce(o.location_id, p.location_id) = loc.id
                                                  and p.sku_id = sku.id
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
                                select p.pick_line_id, p.delivery_line_id, p.sku_id,
                                       coalesce(o.location_id, p.location_id) as location_id,
                                       coalesce(o.replacement_stock_lot_id, p.stock_lot_id) as stock_lot_id,
                                       coalesce(o.replacement_batch_id, p.batch_id) as batch_id,
                                       p.qty
                                  from inventory.pick_list_line p
                                  left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                                 where p.pick_list_id = ?
                                 order by p.delivery_line_id,
                                          coalesce(o.replacement_stock_lot_id, p.stock_lot_id) is null,
                                          coalesce(o.location_id, p.location_id)
                                """,
                                (rs, n) -> new PickListView.Pick(
                                        rs.getObject("pick_line_id", UUID.class),
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
    public List<PickReplacementCandidate> pickReplacementCandidates(
            UUID deliveryDocumentId, UUID pickLineId, ScopeContext scope) {
        Optional<ReplacementLine> line = jdbc
                .query(
                        """
                        select p.owner_entity_id, p.location_id, p.sku_id, p.stock_lot_id, p.qty
                          from inventory.pick_list pl
                          join inventory.pick_list_line p on p.pick_list_id = pl.pick_list_id
                          left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                         where pl.delivery_document_id = ? and p.pick_line_id = ?
                           and pl.status = 'OPEN' and p.stock_lot_id is not null
                           and o.override_id is null
                        """,
                        (rs, n) -> new ReplacementLine(
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("sku_id", UUID.class),
                                rs.getObject("stock_lot_id", UUID.class),
                                rs.getBigDecimal("qty")),
                        deliveryDocumentId,
                        pickLineId)
                .stream()
                .findFirst();
        if (line.isEmpty()) {
            return List.of();
        }

        ReplacementLine selected = line.orElseThrow();
        LocalDate expiresFrom = businessDay.today().plusDays(policy.dispatchMinShelfLifeDays(scope));
        return jdbc.query(
                """
                select candidate.stock_lot_id, candidate.batch_id, candidate.expiry_date,
                       candidate.available_qty
                  from (
                        select l.stock_lot_id, l.batch_id, l.expiry_date,
                               l.qty_on_hand - coalesce((
                                   select sum(other.qty)
                                     from inventory.pick_list_line other
                                     join inventory.pick_list other_list
                                       on other_list.pick_list_id = other.pick_list_id
                                     left join inventory.pick_override other_override
                                       on other_override.pick_line_id = other.pick_line_id
                                    where other_list.status = 'OPEN'
                                      and other.pick_line_id <> ?
                                      and coalesce(other_override.replacement_stock_lot_id,
                                                   other.stock_lot_id) = l.stock_lot_id), 0)
                                   as available_qty
                          from inventory.stock_lot l
                         where l.owner_entity_id = ? and l.location_id = ? and l.sku_id = ?
                           and l.stock_lot_id <> ? and l.condition = 'GOOD'
                           and l.qty_on_hand > 0
                           and (l.expiry_date is null or l.expiry_date >= ?)
                       ) candidate
                 where candidate.available_qty >= ?
                 order by candidate.expiry_date nulls last, candidate.stock_lot_id
                """,
                (rs, n) -> new PickReplacementCandidate(
                        rs.getObject("stock_lot_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("expiry_date", LocalDate.class),
                        rs.getBigDecimal("available_qty")),
                pickLineId,
                selected.ownerEntityId(),
                selected.locationId(),
                selected.skuId(),
                selected.originalStockLotId(),
                Date.valueOf(expiresFrom),
                selected.qty());
    }

    private record ReplacementLine(
            UUID ownerEntityId, UUID locationId, UUID skuId, UUID originalStockLotId, BigDecimal qty) {}

    @Override
    public Optional<OpeningBalanceView> openingBalance(UUID openingBalanceId, ScopeContext scope) {
        List<OpeningBalanceView.Line> lines = jdbc.query(
                """
                select line_no, batch_id, sku_id, condition, qty, unit_cost,
                       batch_no, expiry_date, printed_mrp
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
                        rs.getBigDecimal("unit_cost"),
                        rs.getString("batch_no"),
                        rs.getObject("expiry_date", java.time.LocalDate.class),
                        rs.getBigDecimal("printed_mrp")),
                openingBalanceId);
        return jdbc
                .query(
                        """
                        select opening_balance_id, location_id, status, prepared_by, signed_entity_by,
                               countersigned_by, document_id, rejected_by, rejected_at, rejection_reason
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
                                rs.getObject("rejected_by", UUID.class),
                                rs.getTimestamp("rejected_at") == null
                                        ? null
                                        : rs.getTimestamp("rejected_at").toInstant(),
                                rs.getString("rejection_reason"),
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

    /** Transfer lifecycle derived from its three insert-only source/destination facts. */
    private List<TransferView> transfersWhere(String condition, Object... args) {
        return jdbc.query(
                """
                select t.transfer_id, t.location_id, t.to_location_id, t.issued_by, t.issued_at,
                       d.doc_number_display,
                       x.dispatched_by, x.dispatched_at, x.transfer_id is not null as dispatched,
                       r.received_by, r.received_at, r.transfer_id is not null as received,
                       c.transfer_id is not null as cancelled,
                       s.shortfall_id, s.status as shortfall_status, s.category as shortfall_category
                  from inventory.transfer t
                  left join kernel.document d on d.document_id = t.transfer_id
                  left join inventory.transfer_dispatch x on x.transfer_id = t.transfer_id
                  left join inventory.transfer_receipt r on r.transfer_id = t.transfer_id
                  left join inventory.transfer_cancellation c on c.transfer_id = t.transfer_id
                  left join inventory.transfer_shortfall s on s.transfer_id = t.transfer_id
                 where """
                        + " " + condition
                        + " order by t.issued_at desc, t.transfer_id",
                (rs, n) -> {
                    UUID id = rs.getObject("transfer_id", UUID.class);
                    OffsetDateTime issued = rs.getObject("issued_at", OffsetDateTime.class);
                    OffsetDateTime dispatched = rs.getObject("dispatched_at", OffsetDateTime.class);
                    OffsetDateTime received = rs.getObject("received_at", OffsetDateTime.class);
                    String status = rs.getBoolean("cancelled")
                            ? "CANCELLED"
                            : rs.getBoolean("received")
                                    ? "RECEIVED"
                                    : rs.getBoolean("dispatched") ? "IN_TRANSIT" : "ISSUED";
                    return new TransferView(
                            id,
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("to_location_id", UUID.class),
                            status,
                            rs.getString("doc_number_display"),
                            rs.getObject("issued_by", UUID.class),
                            issued == null ? null : issued.toInstant(),
                            rs.getObject("dispatched_by", UUID.class),
                            dispatched == null ? null : dispatched.toInstant(),
                            rs.getObject("received_by", UUID.class),
                            received == null ? null : received.toInstant(),
                            rs.getObject("shortfall_id", UUID.class),
                            rs.getString("shortfall_status"),
                            rs.getString("shortfall_category"),
                            transferLines(id));
                },
                args);
    }

    private List<TransferView.Line> transferLines(UUID transferId) {
        return jdbc.query(
                """
                select l.line_id, l.line_no, l.batch_id, l.sku_id, l.qty, l.unit_cost,
                       r.received_qty, s.shortfall_qty
                  from inventory.transfer_line l
                  left join inventory.transfer_receipt_line r
                    on r.transfer_id = l.transfer_id and r.transfer_line_id = l.line_id
                  left join inventory.transfer_shortfall_line s
                    on s.transfer_id = l.transfer_id and s.transfer_line_id = l.line_id
                 where l.transfer_id = ?
                 order by l.line_no
                """,
                (rs, n) -> new TransferView.Line(
                        rs.getObject("line_id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("received_qty"),
                        rs.getBigDecimal("shortfall_qty")),
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

    @Override
    public Map<UUID, LocationSalesCost> locationsSalesCost(LocalDate from, LocalDate to, ScopeContext scope) {
        if (from == null || to == null) {
            return Map.of();
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        Map<UUID, LocationSalesCost> result = new LinkedHashMap<>();
        jdbc.query(
                """
                select location_id,
                       coalesce(sum(case when movement_type = 'SALE' then abs(qty_delta) * unit_cost_at_movement
                                         when movement_type = 'SALE_REVERSAL' then -1 * abs(qty_delta) * unit_cost_at_movement
                                         else 0 end), 0) as recognized_cost,
                       bool_or(unit_cost_at_movement <= 0) as has_pending,
                       count(*) as movement_count
                  from inventory.stock_movement
                 where movement_type in ('SALE', 'SALE_REVERSAL')
                   and occurred_at >= ? and occurred_at < ?
                 group by location_id
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    BigDecimal cost = rs.getBigDecimal("recognized_cost");
                    boolean pending = Boolean.TRUE.equals(rs.getObject("has_pending", Boolean.class));
                    long count = rs.getLong("movement_count");
                    result.put(
                            locId,
                            new LocationSalesCost(
                                    locId,
                                    cost != null
                                            ? cost.setScale(2, RoundingMode.HALF_UP)
                                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                                    pending,
                                    count));
                },
                Timestamp.from(start),
                Timestamp.from(end));
        return result;
    }

    @Override
    public List<ReorderControlView> reorderControls(UUID locationId, UUID skuId, ScopeContext scope) {
        return reorderStore.findControls(locationId, skuId, scope);
    }

    @Override
    public List<StockRepricingView> repricings(UUID locationId, UUID skuId, ScopeContext scope) {
        return repriceStore.findRepricings(locationId, skuId, scope);
    }

    @Override
    public List<NearExpiryLotView> nearExpiryLots(
            UUID locationId, Integer windowDays, String tier, ScopeContext scope) {
        LocalDate today = businessDay.today();
        int criticalDays = policy.nearExpiryCriticalDays(scope);
        int warningDays = policy.expiryWarningDays(scope);
        return expiryAlertStore.findNearExpiryLots(
                locationId, windowDays, tier, today, criticalDays, warningDays, scope);
    }

    @Override
    public List<SlowMovingStockView> slowMovingStock(
            UUID locationId, Integer evaluationDays, Integer thresholdDaysOfSupply, ScopeContext scope) {
        LocalDate today = businessDay.today();
        int evalDays = evaluationDays != null ? evaluationDays : policy.slowMovingDays(scope);
        int threshDos = thresholdDaysOfSupply != null ? thresholdDaysOfSupply : policy.slowMovingThresholdDos(scope);
        return expiryAlertStore.findSlowMovingStock(locationId, evalDays, threshDos, today, zone, scope);
    }

    @Override
    public List<StockSummaryView> stockSummaries(
            UUID locationId,
            UUID departmentId,
            String uomCode,
            UUID skuId,
            boolean includeZero,
            boolean discrepancyOnly,
            ScopeContext scope) {
        if (locationId == null) {
            return List.of();
        }

        Date today = today();

        List<LotBalance> lots = balances(locationId, skuId, true, scope);
        Map<UUID, List<LotBalance>> lotsBySku = new LinkedHashMap<>();
        for (LotBalance lot : lots) {
            lotsBySku.computeIfAbsent(lot.skuId(), k -> new ArrayList<>()).add(lot);
        }

        Map<UUID, BigDecimal> reservationsBySku = new HashMap<>();
        jdbc.query(
                """
                select p.sku_id, coalesce(sum(p.qty), 0) as reserved_qty
                  from inventory.pick_list_line p
                  join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                  left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                  join inventory.stock_lot r on r.stock_lot_id = coalesce(o.replacement_stock_lot_id, p.stock_lot_id)
                 where pl.status = 'OPEN'
                   and coalesce(o.location_id, p.location_id) = ?
                   and (r.expiry_date is null or r.expiry_date >= ?)
                 group by p.sku_id
                """,
                rs -> {
                    reservationsBySku.put(rs.getObject("sku_id", UUID.class), rs.getBigDecimal("reserved_qty"));
                },
                locationId,
                today);

        Map<UUID, BigDecimal> incomingTransfersBySku = new HashMap<>();
        jdbc.query(
                """
                select tl.sku_id, coalesce(sum(tl.qty), 0) as incoming_qty
                  from inventory.transfer t
                  join inventory.transfer_line tl on tl.transfer_id = t.transfer_id
                  join inventory.transfer_dispatch x on x.transfer_id = t.transfer_id
                  left join inventory.transfer_receipt r on r.transfer_id = t.transfer_id
                  left join inventory.transfer_cancellation c on c.transfer_id = t.transfer_id
                 where t.to_location_id = ?
                   and r.transfer_id is null
                   and c.transfer_id is null
                 group by tl.sku_id
                """,
                rs -> {
                    incomingTransfersBySku.put(rs.getObject("sku_id", UUID.class), rs.getBigDecimal("incoming_qty"));
                },
                locationId);

        Map<UUID, BigDecimal> outgoingTransfersBySku = new HashMap<>();
        jdbc.query(
                """
                select tl.sku_id, coalesce(sum(tl.qty), 0) as outgoing_qty
                  from inventory.transfer t
                  join inventory.transfer_line tl on tl.transfer_id = t.transfer_id
                  join inventory.transfer_dispatch x on x.transfer_id = t.transfer_id
                  left join inventory.transfer_receipt r on r.transfer_id = t.transfer_id
                  left join inventory.transfer_cancellation c on c.transfer_id = t.transfer_id
                 where t.location_id = ?
                   and r.transfer_id is null
                   and c.transfer_id is null
                 group by tl.sku_id
                """,
                rs -> {
                    outgoingTransfersBySku.put(rs.getObject("sku_id", UUID.class), rs.getBigDecimal("outgoing_qty"));
                },
                locationId);

        record SkuCountVariance(BigDecimal varianceQty, BigDecimal varianceValue, String status, Instant countedAt) {}
        Map<UUID, SkuCountVariance> countVariancesBySku = new HashMap<>();
        List<Map<String, Object>> latestTaskRows = jdbc.queryForList(
                """
                select task_id, status, outcome, scheduled_at, started_at, submitted_at
                  from inventory.count_task
                 where location_id = ?
                 order by scheduled_at desc, task_id desc
                 limit 1
                """,
                locationId);

        if (!latestTaskRows.isEmpty()) {
            Map<String, Object> taskRow = latestTaskRows.get(0);
            UUID taskId = (UUID) taskRow.get("task_id");
            String taskStatus = (String) taskRow.get("status");
            String taskOutcome = (String) taskRow.get("outcome");
            Timestamp submittedAtTs = (Timestamp) taskRow.get("submitted_at");
            Timestamp startedAtTs = (Timestamp) taskRow.get("started_at");
            Instant countTimestamp = submittedAtTs != null
                    ? submittedAtTs.toInstant()
                    : (startedAtTs != null ? startedAtTs.toInstant() : null);

            List<Map<String, Object>> countLines = jdbc.queryForList(
                    """
                    select sku_id,
                           coalesce(sum(variance_qty), 0) as var_qty,
                           coalesce(sum(variance_value), 0) as var_val
                      from inventory.count_line
                     where task_id = ?
                     group by sku_id
                    """,
                    taskId);

            if (!countLines.isEmpty()) {
                String effectiveStatus = taskOutcome != null ? taskOutcome : taskStatus;
                for (Map<String, Object> line : countLines) {
                    UUID sId = (UUID) line.get("sku_id");
                    BigDecimal vQty = (BigDecimal) line.get("var_qty");
                    BigDecimal vVal = (BigDecimal) line.get("var_val");
                    String statusLabel =
                            vQty != null && vQty.compareTo(BigDecimal.ZERO) == 0 ? "MATCHED" : effectiveStatus;
                    countVariancesBySku.put(sId, new SkuCountVariance(vQty, vVal, statusLabel, countTimestamp));
                }
            } else {
                List<Map<String, Object>> scanVariances = jdbc.queryForList(
                        """
                        select coalesce(e.sku_id, s.sku_id) as sku_id,
                               coalesce(s.scanned_qty, 0) - coalesce(e.exp_qty, 0) as var_qty
                          from (select sku_id, sum(expected_qty) as exp_qty
                                  from inventory.count_expectation
                                 where task_id = ? group by sku_id) e
                          full outer join (select sku_id, sum(counted_qty) as scanned_qty
                                             from inventory.stock_take_scan
                                            where task_id = ? group by sku_id) s on s.sku_id = e.sku_id
                        """,
                        taskId,
                        taskId);
                for (Map<String, Object> line : scanVariances) {
                    UUID sId = (UUID) line.get("sku_id");
                    BigDecimal vQty = (BigDecimal) line.get("var_qty");
                    if (sId != null && vQty != null) {
                        String statusLabel = vQty.compareTo(BigDecimal.ZERO) == 0 ? "MATCHED" : "IN_PROGRESS";
                        countVariancesBySku.put(
                                sId, new SkuCountVariance(vQty, BigDecimal.ZERO, statusLabel, countTimestamp));
                    }
                }
            }
        }

        Set<UUID> allSkuIds = new LinkedHashSet<>(lotsBySku.keySet());
        allSkuIds.addAll(incomingTransfersBySku.keySet());
        allSkuIds.addAll(outgoingTransfersBySku.keySet());
        allSkuIds.addAll(reservationsBySku.keySet());

        if (skuId != null) {
            allSkuIds.removeIf(id -> !id.equals(skuId));
        }

        Map<UUID, DepartmentCategoryView> deptMap = new HashMap<>();
        try {
            for (DepartmentCategoryView dept : catalogueQueries.departmentCategories(scope)) {
                deptMap.put(dept.departmentId(), dept);
            }
        } catch (Exception ignored) {
        }

        List<StockSummaryView> summaries = new ArrayList<>();
        for (UUID sId : allSkuIds) {
            Optional<SkuView> skuOpt = catalogueQueries.getSku(sId, scope);
            if (skuOpt.isEmpty()) {
                continue;
            }
            SkuView sku = skuOpt.get();

            if (departmentId != null && !departmentId.equals(sku.departmentId())) {
                continue;
            }

            if (uomCode != null && !uomCode.equalsIgnoreCase(sku.baseUomCode())) {
                continue;
            }

            List<LotBalance> skuLots = lotsBySku.getOrDefault(sId, List.of());
            BigDecimal onHand = skuLots.stream().map(LotBalance::qtyOnHand).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal reserved = reservationsBySku.getOrDefault(sId, BigDecimal.ZERO);
            BigDecimal incoming = incomingTransfersBySku.getOrDefault(sId, BigDecimal.ZERO);
            BigDecimal outgoing = outgoingTransfersBySku.getOrDefault(sId, BigDecimal.ZERO);

            SkuCountVariance countVar = countVariancesBySku.get(sId);
            BigDecimal varianceQty = countVar != null ? countVar.varianceQty() : null;
            BigDecimal varianceVal = countVar != null ? countVar.varianceValue() : null;
            String countStatus = countVar != null ? countVar.status() : null;
            Instant countTime = countVar != null ? countVar.countedAt() : null;

            if (!includeZero && onHand.compareTo(BigDecimal.ZERO) == 0) {
                boolean hasVariance = varianceQty != null && varianceQty.compareTo(BigDecimal.ZERO) != 0;
                if (incoming.compareTo(BigDecimal.ZERO) == 0
                        && reserved.compareTo(BigDecimal.ZERO) == 0
                        && !hasVariance) {
                    continue;
                }
            }

            BigDecimal goodInDateOnHand = skuLots.stream()
                    .filter(l -> "GOOD".equals(l.condition())
                            && !l.expired()
                            && l.qtyOnHand().compareTo(BigDecimal.ZERO) > 0)
                    .map(LotBalance::qtyOnHand)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal available = nonNegative(goodInDateOnHand.subtract(reserved));

            if (discrepancyOnly) {
                boolean hasDiscrepancy = (varianceQty != null && varianceQty.compareTo(BigDecimal.ZERO) != 0)
                        || onHand.compareTo(BigDecimal.ZERO) < 0
                        || skuLots.stream().anyMatch(LotBalance::negative);
                if (!hasDiscrepancy) {
                    continue;
                }
            }

            BigDecimal remaining = available.add(incoming);
            DepartmentCategoryView dept = sku.departmentId() != null ? deptMap.get(sku.departmentId()) : null;

            summaries.add(new StockSummaryView(
                    locationId,
                    sId,
                    sku.skuCode(),
                    sku.nameEn(),
                    sku.nameSi(),
                    sku.nameTa(),
                    sku.baseUomCode(),
                    sku.departmentId(),
                    dept != null ? dept.nameEn() : null,
                    dept != null ? dept.nameSi() : null,
                    dept != null ? dept.nameTa() : null,
                    onHand,
                    reserved,
                    available,
                    incoming,
                    outgoing,
                    varianceQty,
                    varianceVal,
                    countStatus,
                    countTime,
                    remaining,
                    skuLots.size(),
                    skuLots));
        }

        summaries.sort((a, b) -> a.skuCode().compareToIgnoreCase(b.skuCode()));
        return summaries;
    }

    @Override
    public Optional<StockExplanationView> stockExplanation(UUID locationId, UUID skuId, ScopeContext scope) {
        if (locationId == null || skuId == null) {
            return Optional.empty();
        }

        Optional<SkuView> skuOpt = catalogueQueries.getSku(skuId, scope);
        if (skuOpt.isEmpty()) {
            return Optional.empty();
        }
        SkuView sku = skuOpt.get();

        Date today = today();
        List<LotBalance> lots = balances(locationId, skuId, true, scope);

        BigDecimal onHand = lots.stream().map(LotBalance::qtyOnHand).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StockExplanationView.ReservationDetail> reservations = jdbc.query(
                """
                select p.pick_list_id, pl.delivery_document_id, d.doc_number_display,
                       p.qty as reserved_qty, pl.status as pick_status, pl.created_at as reserved_at
                  from inventory.pick_list_line p
                  join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                  left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                  join inventory.stock_lot r on r.stock_lot_id = coalesce(o.replacement_stock_lot_id, p.stock_lot_id)
                  left join kernel.document d on d.document_id = pl.delivery_document_id
                 where pl.status = 'OPEN'
                   and coalesce(o.location_id, p.location_id) = ?
                   and p.sku_id = ?
                   and (r.expiry_date is null or r.expiry_date >= ?)
                 order by pl.created_at desc
                """,
                (rs, n) -> new StockExplanationView.ReservationDetail(
                        rs.getObject("pick_list_id", UUID.class),
                        rs.getObject("delivery_document_id", UUID.class),
                        rs.getString("doc_number_display"),
                        rs.getBigDecimal("reserved_qty"),
                        rs.getString("pick_status"),
                        rs.getTimestamp("reserved_at") != null
                                ? rs.getTimestamp("reserved_at").toInstant()
                                : null),
                locationId,
                skuId,
                today);

        BigDecimal reserved = reservations.stream()
                .map(StockExplanationView.ReservationDetail::reservedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal goodInDateOnHand = lots.stream()
                .filter(l -> "GOOD".equals(l.condition())
                        && !l.expired()
                        && l.qtyOnHand().compareTo(BigDecimal.ZERO) > 0)
                .map(LotBalance::qtyOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = nonNegative(goodInDateOnHand.subtract(reserved));

        List<StockExplanationView.InTransitDetail> incomingTransfers = jdbc.query(
                """
                select t.transfer_id, d.doc_number_display, t.location_id as from_loc_id,
                       tl.qty, x.dispatched_at, 'IN_TRANSIT' as status
                  from inventory.transfer t
                  join inventory.transfer_line tl on tl.transfer_id = t.transfer_id
                  join inventory.transfer_dispatch x on x.transfer_id = t.transfer_id
                  left join inventory.transfer_receipt r on r.transfer_id = t.transfer_id
                  left join inventory.transfer_cancellation c on c.transfer_id = t.transfer_id
                  left join kernel.document d on d.document_id = t.transfer_id
                 where t.to_location_id = ?
                   and tl.sku_id = ?
                   and r.transfer_id is null
                   and c.transfer_id is null
                 order by x.dispatched_at desc
                """,
                (rs, n) -> {
                    UUID fromLocId = rs.getObject("from_loc_id", UUID.class);
                    String fromLocName = partyQueries
                            .getLocation(fromLocId, scope)
                            .map(l -> l.nameEn())
                            .orElse(null);
                    return new StockExplanationView.InTransitDetail(
                            rs.getObject("transfer_id", UUID.class),
                            rs.getString("doc_number_display"),
                            fromLocId,
                            fromLocName,
                            rs.getBigDecimal("qty"),
                            rs.getTimestamp("dispatched_at") != null
                                    ? rs.getTimestamp("dispatched_at").toInstant()
                                    : null,
                            rs.getString("status"));
                },
                locationId,
                skuId);

        BigDecimal inTransitIncoming = incomingTransfers.stream()
                .map(StockExplanationView.InTransitDetail::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StockExplanationView.InTransitDetail> outgoingTransfers = jdbc.query(
                """
                select t.transfer_id, d.doc_number_display, t.to_location_id as to_loc_id,
                       tl.qty, x.dispatched_at, 'IN_TRANSIT' as status
                  from inventory.transfer t
                  join inventory.transfer_line tl on tl.transfer_id = t.transfer_id
                  join inventory.transfer_dispatch x on x.transfer_id = t.transfer_id
                  left join inventory.transfer_receipt r on r.transfer_id = t.transfer_id
                  left join inventory.transfer_cancellation c on c.transfer_id = t.transfer_id
                  left join kernel.document d on d.document_id = t.transfer_id
                 where t.location_id = ?
                   and tl.sku_id = ?
                   and r.transfer_id is null
                   and c.transfer_id is null
                 order by x.dispatched_at desc
                """,
                (rs, n) -> {
                    UUID toLocId = rs.getObject("to_loc_id", UUID.class);
                    String toLocName = partyQueries
                            .getLocation(toLocId, scope)
                            .map(l -> l.nameEn())
                            .orElse(null);
                    return new StockExplanationView.InTransitDetail(
                            rs.getObject("transfer_id", UUID.class),
                            rs.getString("doc_number_display"),
                            toLocId,
                            toLocName,
                            rs.getBigDecimal("qty"),
                            rs.getTimestamp("dispatched_at") != null
                                    ? rs.getTimestamp("dispatched_at").toInstant()
                                    : null,
                            rs.getString("status"));
                },
                locationId,
                skuId);

        BigDecimal inTransitOutgoing = outgoingTransfers.stream()
                .map(StockExplanationView.InTransitDetail::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remaining = available.add(inTransitIncoming);

        List<StockExplanationView.CountDetail> countDetails = jdbc.query(
                """
                select ct.task_id, ct.scope_kind, ct.status, ct.outcome, ct.started_at, ct.submitted_at,
                       coalesce(cl.expected_qty, exp.expected_qty, 0) as expected_qty,
                       coalesce(cl.counted_qty, scans.counted_qty, 0) as counted_qty,
                       coalesce(cl.variance_qty, coalesce(scans.counted_qty, 0) - coalesce(exp.expected_qty, 0)) as variance_qty,
                       coalesce(cl.variance_value, 0) as variance_value
                  from inventory.count_task ct
                  left join (select task_id, sum(expected_qty) as expected_qty, sum(counted_qty) as counted_qty,
                                    sum(variance_qty) as variance_qty, sum(variance_value) as variance_value
                               from inventory.count_line where sku_id = ? group by task_id) cl on cl.task_id = ct.task_id
                  left join (select task_id, sum(expected_qty) as expected_qty
                               from inventory.count_expectation where sku_id = ? group by task_id) exp on exp.task_id = ct.task_id
                  left join (select task_id, sum(counted_qty) as counted_qty
                               from inventory.stock_take_scan where sku_id = ? group by task_id) scans on scans.task_id = ct.task_id
                 where ct.location_id = ?
                   and (cl.task_id is not null or exp.task_id is not null or scans.task_id is not null)
                 order by ct.scheduled_at desc, ct.task_id desc
                 limit 1
                """,
                (rs, n) -> new StockExplanationView.CountDetail(
                        rs.getObject("task_id", UUID.class),
                        rs.getString("scope_kind"),
                        rs.getString("status"),
                        rs.getString("outcome"),
                        rs.getBigDecimal("expected_qty"),
                        rs.getBigDecimal("counted_qty"),
                        rs.getBigDecimal("variance_qty"),
                        rs.getBigDecimal("variance_value"),
                        rs.getTimestamp("started_at") != null
                                ? rs.getTimestamp("started_at").toInstant()
                                : null,
                        rs.getTimestamp("submitted_at") != null
                                ? rs.getTimestamp("submitted_at").toInstant()
                                : null),
                skuId,
                skuId,
                skuId,
                locationId);

        StockExplanationView.CountDetail latestCount = countDetails.isEmpty() ? null : countDetails.get(0);
        BigDecimal varianceQty = latestCount != null ? latestCount.varianceQty() : null;

        DepartmentCategoryView dept = null;
        if (sku.departmentId() != null) {
            try {
                dept = catalogueQueries
                        .getDepartmentCategory(sku.departmentId(), scope)
                        .orElse(null);
            } catch (Exception ignored) {
            }
        }

        return Optional.of(new StockExplanationView(
                locationId,
                skuId,
                sku.skuCode(),
                sku.nameEn(),
                sku.nameSi(),
                sku.nameTa(),
                sku.baseUomCode(),
                sku.departmentId(),
                dept != null ? dept.nameEn() : null,
                dept != null ? dept.nameSi() : null,
                dept != null ? dept.nameTa() : null,
                onHand,
                reserved,
                available,
                inTransitIncoming,
                inTransitOutgoing,
                varianceQty,
                remaining,
                lots,
                reservations,
                incomingTransfers,
                outgoingTransfers,
                latestCount));
    }
}
