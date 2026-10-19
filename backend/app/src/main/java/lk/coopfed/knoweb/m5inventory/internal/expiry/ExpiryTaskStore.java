package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only store for expiry task discovery and queries (ArchitectureTests: only command handlers write).
 */
@Component
public class ExpiryTaskStore {

    public record ExpiryTask(
            UUID taskId,
            UUID ownerEntityId,
            UUID locationId,
            UUID batchId,
            UUID skuId,
            int daysToExpiry,
            BigDecimal qtyAtOpen,
            String status,
            UUID countTaskId,
            UUID suggestedWriteoffId,
            Instant createdAt,
            Instant closedAt) {}

    public record CandidateLot(
            UUID lotId,
            UUID ownerEntityId,
            UUID locationId,
            UUID batchId,
            UUID skuId,
            BigDecimal qtyOnHand,
            LocalDate expiryDate) {}

    public record OpenCount(UUID taskId, String scopeKind, List<UUID> scopeSkuIds, String status) {}

    private final JdbcTemplate jdbc;

    ExpiryTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Eligible active GOOD lots with future expiry dates within 365 days that do not already have an open expiry task.
     * Evaluated using a federation-wide or own scope.
     */
    public List<CandidateLot> findCandidateLots(LocalDate today, ScopeContext scope) {
        LocalDate maxExpiry = today.plusDays(365);
        return jdbc.query(
                """
                select l.stock_lot_id, l.owner_entity_id, l.location_id, l.batch_id, l.sku_id,
                       l.qty_on_hand, l.expiry_date
                  from inventory.stock_lot l
                 where l.qty_on_hand > 0
                   and l.condition = 'GOOD'
                   and l.expiry_date is not null
                   and l.expiry_date >= ?
                   and l.expiry_date <= ?
                   and not exists (
                       select 1 from inventory.expiry_task e
                        where e.location_id = l.location_id
                          and e.batch_id = l.batch_id
                          and e.status <> 'CLOSED'
                   )
                 order by l.owner_entity_id, l.location_id, l.expiry_date, l.batch_id
                """,
                (rs, n) -> new CandidateLot(
                        rs.getObject("stock_lot_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("qty_on_hand"),
                        rs.getObject("expiry_date", LocalDate.class)),
                today,
                maxExpiry);
    }

    public record LocationRef(UUID ownerEntityId, UUID locationId) {}

    /**
     * Locations that currently have active (OPEN or COUNTED) expiry tasks across the fleet.
     * Evaluated using a federation-wide or own scope.
     */
    public List<LocationRef> findActiveTaskLocations(ScopeContext scope) {
        return jdbc.query(
                """
                select distinct owner_entity_id, location_id
                  from inventory.expiry_task
                 where status in ('OPEN', 'COUNTED')
                 order by owner_entity_id, location_id
                """,
                (rs, n) -> new LocationRef(
                        rs.getObject("owner_entity_id", UUID.class), rs.getObject("location_id", UUID.class)));
    }

    /**
     * Active count task for a location (status <> 'CLOSED').
     */
    public Optional<OpenCount> findOpenCountTask(UUID locationId) {
        return jdbc
                .query(
                        """
                        select task_id, scope_kind, scope_sku_ids, status
                          from inventory.count_task
                         where location_id = ?
                           and status <> 'CLOSED'
                        """,
                        (rs, n) -> {
                            Array skus = rs.getArray("scope_sku_ids");
                            List<UUID> skuList = skus == null ? List.of() : Arrays.asList((UUID[]) skus.getArray());
                            return new OpenCount(
                                    rs.getObject("task_id", UUID.class),
                                    rs.getString("scope_kind"),
                                    skuList,
                                    rs.getString("status"));
                        },
                        locationId)
                .stream()
                .findFirst();
    }

    /**
     * Expiry tasks at a location that are OPEN but currently have no linked count task.
     */
    public List<ExpiryTask> findPendingUnlinkedTasks(UUID locationId) {
        return jdbc.query(
                """
                select task_id, owner_entity_id, location_id, batch_id, sku_id, days_to_expiry,
                       qty_at_open, status, count_task_id, suggested_writeoff_id, created_at, closed_at
                  from inventory.expiry_task
                 where location_id = ?
                   and status = 'OPEN'
                   and count_task_id is null
                 order by created_at, task_id
                """,
                this::mapExpiryTask,
                locationId);
    }

    /**
     * Active (OPEN or COUNTED) expiry tasks at a location.
     */
    public List<ExpiryTask> findActiveTasks(UUID locationId) {
        return jdbc.query(
                """
                select task_id, owner_entity_id, location_id, batch_id, sku_id, days_to_expiry,
                       qty_at_open, status, count_task_id, suggested_writeoff_id, created_at, closed_at
                  from inventory.expiry_task
                 where location_id = ?
                   and status in ('OPEN', 'COUNTED')
                 order by created_at, task_id
                """,
                this::mapExpiryTask,
                locationId);
    }

    /**
     * Find an expiry task by ID.
     */
    public Optional<ExpiryTask> findById(UUID taskId) {
        return jdbc
                .query(
                        """
                        select task_id, owner_entity_id, location_id, batch_id, sku_id, days_to_expiry,
                               qty_at_open, status, count_task_id, suggested_writeoff_id, created_at, closed_at
                          from inventory.expiry_task
                         where task_id = ?
                        """,
                        this::mapExpiryTask,
                        taskId)
                .stream()
                .findFirst();
    }

    /**
     * Current on-hand quantity for a GOOD lot of a batch at a location, or zero if missing.
     */
    public BigDecimal currentOnHand(UUID locationId, UUID batchId) {
        return jdbc
                .query(
                        """
                        select qty_on_hand
                          from inventory.stock_lot
                         where location_id = ?
                           and batch_id = ?
                           and condition = 'GOOD'
                        """,
                        (rs, n) -> rs.getBigDecimal("qty_on_hand"),
                        locationId,
                        batchId)
                .stream()
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private ExpiryTask mapExpiryTask(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        OffsetDateTime closed = rs.getObject("closed_at", OffsetDateTime.class);
        return new ExpiryTask(
                rs.getObject("task_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getInt("days_to_expiry"),
                rs.getBigDecimal("qty_at_open"),
                rs.getString("status"),
                rs.getObject("count_task_id", UUID.class),
                rs.getObject("suggested_writeoff_id", UUID.class),
                created == null ? null : created.toInstant(),
                closed == null ? null : closed.toInstant());
    }
}
