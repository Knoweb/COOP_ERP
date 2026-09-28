package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.AcknowledgeNegativeLot;
import lk.coopfed.knoweb.m5inventory.api.LotAcknowledged;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AcknowledgeNegativeLot (25A section 6.3: "entity scope; note → acknowledged_at"; doc 25 section
 * 5.1 gives it inv.count.schedule): a lot the tills oversold (M6, flagged {@code lot.negative.v1})
 * has been looked at. The stock is not changed: the next count corrects it.
 *
 * <p>Guards, in order: an OWN scope; the lot visible ({@code m5.lot.not_found}); below zero
 * ({@code m5.lot.not_negative}); not acknowledged since it went below ({@code m5.lot.already_acknowledged});
 * a note ({@code m5.reason_required}).
 *
 * <p>Mutation: the lot's {@code negative_acknowledged_at}. Audit {@code STOCK_LOT_ACKNOWLEDGED}
 * with the note; event {@code lot.acknowledged.v1}.
 */
@Service
@CommandHandler(permission = "inv.count.schedule")
class AcknowledgeNegativeLotHandler implements Handles<AcknowledgeNegativeLot, UUID> {

    static final String AUDIT_ACKNOWLEDGED = "STOCK_LOT_ACKNOWLEDGED";

    private record Lot(
            UUID ownerEntityId,
            UUID locationId,
            BigDecimal qtyOnHand,
            OffsetDateTime negativeSince,
            OffsetDateTime acknowledgedAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    AcknowledgeNegativeLotHandler(JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AcknowledgeNegativeLot command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        Lot lot = jdbc
                .query(
                        """
                        select owner_entity_id, location_id, qty_on_hand, negative_since, negative_acknowledged_at
                          from inventory.stock_lot
                         where stock_lot_id = ?
                           for update
                        """,
                        (rs, n) -> new Lot(
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getBigDecimal("qty_on_hand"),
                                rs.getObject("negative_since", OffsetDateTime.class),
                                rs.getObject("negative_acknowledged_at", OffsetDateTime.class)),
                        command.stockLotId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException(
                        "m5.lot.not_found", Map.of("stockLotId", String.valueOf(command.stockLotId()))));
        if (lot.qtyOnHand().signum() >= 0 || lot.negativeSince() == null) {
            throw new ProblemException("m5.lot.not_negative");
        }
        if (lot.acknowledgedAt() != null && !lot.acknowledgedAt().isBefore(lot.negativeSince())) {
            throw new ProblemException("m5.lot.already_acknowledged");
        }
        if (command.note() == null || command.note().isBlank()) {
            throw new ProblemException("m5.reason_required");
        }

        jdbc.update(
                "update inventory.stock_lot set negative_acknowledged_at = ? where stock_lot_id = ?",
                Timestamp.from(clock.instant()),
                command.stockLotId());

        audit.record(
                AUDIT_ACKNOWLEDGED,
                Subject.of("stock_lot", command.stockLotId()),
                null,
                Map.of("qtyOnHand", lot.qtyOnHand().toPlainString(), "locationId", lot.locationId()),
                scope,
                command.note().strip());
        events.publish(
                new LotAcknowledged(command.stockLotId(), lot.ownerEntityId(), lot.locationId(), lot.qtyOnHand()));
        return command.stockLotId();
    }
}
