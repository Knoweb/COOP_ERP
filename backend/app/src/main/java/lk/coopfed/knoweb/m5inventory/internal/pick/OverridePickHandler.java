package lk.coopfed.knoweb.m5inventory.internal.pick;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.OverridePick;
import lk.coopfed.knoweb.m5inventory.api.PickOverridden;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements the manual FEFO exception of FR-INV-040 without changing the suggested pick. */
@Service
@CommandHandler(permission = "whs.pick")
class OverridePickHandler implements Handles<OverridePick, UUID> {

    static final String AUDIT_OVERRIDDEN = "PICK_OVERRIDDEN";

    private final JdbcTemplate jdbc;
    private final BusinessDay businessDay;
    private final ControlPolicy policy;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    OverridePickHandler(
            JdbcTemplate jdbc,
            BusinessDay businessDay,
            ControlPolicy policy,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.businessDay = businessDay;
        this.policy = policy;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(OverridePick command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (scope.userId() == null) {
            throw new ProblemException("m5.pick.user_required");
        }
        if (command.reason() == null
                || command.reason().isBlank()
                || command.reason().strip().length() > 500) {
            throw new ProblemException("m5.pick.reason_invalid");
        }

        PickList pickList = jdbc
                .query(
                        "select pick_list_id, owner_entity_id, status from inventory.pick_list"
                                + " where delivery_document_id = ? for update",
                        (rs, n) -> new PickList(
                                rs.getObject("pick_list_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getString("status")),
                        command.deliveryNoteId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("m5.pick_list.not_found"));
        if (!pickList.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m5.pick_list.not_found");
        }
        if (!"OPEN".equals(pickList.status())) {
            throw new ProblemException("m5.pick_list.not_open");
        }

        PickLine line = jdbc
                .query(
                        "select pick_line_id, owner_entity_id, location_id, stock_lot_id, sku_id, qty"
                                + " from inventory.pick_list_line where pick_list_id = ? and pick_line_id = ?",
                        (rs, n) -> new PickLine(
                                rs.getObject("pick_line_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("stock_lot_id", UUID.class),
                                rs.getObject("sku_id", UUID.class),
                                rs.getBigDecimal("qty")),
                        pickList.pickListId(),
                        command.pickLineId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("m5.pick.line_not_found"));
        if (line.stockLotId() == null) {
            throw new ProblemException("m5.pick.short_line_not_overridable");
        }
        if (line.stockLotId().equals(command.replacementStockLotId())) {
            throw new ProblemException("m5.pick.replacement_same_as_original");
        }
        if (jdbc.queryForObject(
                "select exists(select 1 from inventory.pick_override where pick_line_id = ?)",
                Boolean.class,
                line.pickLineId())) {
            throw new ProblemException("m5.pick.already_overridden");
        }

        Lot replacement = jdbc
                .query(
                        "select stock_lot_id, owner_entity_id, location_id, sku_id, batch_id, condition,"
                                + " qty_on_hand, expiry_date from inventory.stock_lot"
                                + " where stock_lot_id = ? for update",
                        (rs, n) -> new Lot(
                                rs.getObject("stock_lot_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("sku_id", UUID.class),
                                rs.getObject("batch_id", UUID.class),
                                rs.getString("condition"),
                                rs.getBigDecimal("qty_on_hand"),
                                rs.getDate("expiry_date") == null
                                        ? null
                                        : rs.getDate("expiry_date").toLocalDate()),
                        command.replacementStockLotId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("m5.pick.replacement_lot_not_found"));

        if (!replacement.ownerEntityId().equals(line.ownerEntityId())) {
            throw new ProblemException("m5.pick.replacement_lot_not_found");
        }
        if (!replacement.skuId().equals(line.skuId())) {
            throw new ProblemException("m5.pick.sku_mismatch");
        }
        if (!replacement.locationId().equals(line.locationId())) {
            throw new ProblemException("m5.pick.location_mismatch");
        }
        if (!"GOOD".equals(replacement.condition()) || replacement.qtyOnHand().signum() <= 0) {
            throw new ProblemException("m5.pick.lot_unavailable");
        }
        LocalDate expiresFrom = businessDay.today().plusDays(policy.dispatchMinShelfLifeDays(scope));
        if (replacement.expiryDate() != null && replacement.expiryDate().isBefore(expiresFrom)) {
            throw new ProblemException("m5.pick.lot_expired", Map.of("expiresFrom", expiresFrom));
        }

        BigDecimal reservedByOthers = jdbc.queryForObject(
                """
                select coalesce(sum(p.qty), 0)
                  from inventory.pick_list_line p
                  join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                  left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                 where pl.status = 'OPEN' and p.pick_line_id <> ?
                   and coalesce(o.replacement_stock_lot_id, p.stock_lot_id) = ?
                """,
                BigDecimal.class,
                line.pickLineId(),
                replacement.stockLotId());
        BigDecimal available =
                replacement.qtyOnHand().subtract(reservedByOthers == null ? BigDecimal.ZERO : reservedByOthers);
        if (available.compareTo(line.qty()) < 0) {
            throw new ProblemException(
                    "m5.pick.lot_unavailable",
                    Map.of("available", available.max(BigDecimal.ZERO), "required", line.qty()));
        }

        UUID overrideId = Ids.next();
        String reason = command.reason().strip();
        jdbc.update(
                """
                insert into inventory.pick_override
                    (override_id, pick_line_id, pick_list_id, owner_entity_id, location_id, sku_id,
                     original_stock_lot_id, replacement_stock_lot_id, replacement_batch_id, qty,
                     reason, overridden_by, overridden_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                overrideId,
                line.pickLineId(),
                pickList.pickListId(),
                line.ownerEntityId(),
                line.locationId(),
                line.skuId(),
                line.stockLotId(),
                replacement.stockLotId(),
                replacement.batchId(),
                line.qty(),
                reason,
                scope.userId(),
                Timestamp.from(clock.instant()));

        audit.record(
                AUDIT_OVERRIDDEN,
                Subject.of("pick_override", overrideId),
                Map.of("stockLotId", line.stockLotId()),
                Map.of(
                        "pickListId", pickList.pickListId(),
                        "pickLineId", line.pickLineId(),
                        "stockLotId", replacement.stockLotId(),
                        "qty", line.qty()),
                scope,
                reason);
        events.publish(new PickOverridden(
                overrideId,
                line.ownerEntityId(),
                pickList.pickListId(),
                line.pickLineId(),
                line.stockLotId(),
                replacement.stockLotId(),
                line.qty(),
                reason));
        return overrideId;
    }

    private record PickList(UUID pickListId, UUID ownerEntityId, String status) {}

    private record PickLine(
            UUID pickLineId, UUID ownerEntityId, UUID locationId, UUID stockLotId, UUID skuId, BigDecimal qty) {}

    private record Lot(
            UUID stockLotId,
            UUID ownerEntityId,
            UUID locationId,
            UUID skuId,
            UUID batchId,
            String condition,
            BigDecimal qtyOnHand,
            LocalDate expiryDate) {}
}
