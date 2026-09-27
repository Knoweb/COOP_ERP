package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PickListDispatched;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vehicle left with the delivery note's goods (doc 24 section 4.2, ISSUED to IN_TRANSIT). The
 * picked units leave the seller's lots as TRANSFER_OUT movements citing the delivery note: in
 * transit, and still the seller's until the buyer confirms the GRN (doc 24 A-03: in-transit loss is
 * the seller's; AGENTS.md idea 2), when M5 receives them into the buyer's lots. The reservation
 * ends: the units are no longer on hand.
 *
 * <p>Guards: the seller's OWN scope ({@code m5.delivery.seller_mismatch}); the delivery note's pick
 * list ({@code m5.pick_list.not_found}: the issue event is applied first). A pick list already
 * dispatched is not dispatched twice.
 *
 * <p>Audit {@code PICK_LIST_DISPATCHED}; event {@code pick_list.dispatched.v1} (and the ledger's
 * {@code stock.moved.v1}). Permission {@code whs.pick}, as for the reservation.
 */
@Service
@CommandHandler(permission = "whs.pick")
class DispatchDeliveryHandler implements Handles<DispatchDelivery, Integer> {

    static final String AUDIT_DISPATCHED = "PICK_LIST_DISPATCHED";

    private final ConsumerStore store;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    DispatchDeliveryHandler(
            ConsumerStore store,
            StockLedger ledger,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Integer handle(DispatchDelivery command, ScopeContext scope) {
        ConsumerGuards.requireScopeOf(command.sellerEntityId(), scope, "m5.delivery.seller_mismatch");
        ConsumerStore.PickList list = store.pickListOf(command.deliveryNoteId())
                .orElseThrow(() -> new ProblemException(
                        "m5.pick_list.not_found", Map.of("deliveryDocumentId", command.deliveryNoteId())));
        if (!"OPEN".equals(list.status())) {
            return 0;
        }

        Instant dispatchedAt = command.dispatchedAt() == null ? clock.instant() : command.dispatchedAt();
        List<Movement> movements = store.lotPicks(list.pickListId()).stream()
                .map(pick -> new Movement(
                        pick.locationId(),
                        pick.batchId(),
                        LotCondition.GOOD,
                        MovementType.TRANSFER_OUT,
                        pick.qty().negate(),
                        null,
                        null))
                .toList();
        jdbc.update(
                "update inventory.pick_list set status = 'DISPATCHED', dispatched_at = ? where pick_list_id = ?",
                Timestamp.from(dispatchedAt),
                list.pickListId());
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(command.deliveryNoteId(), dispatchedAt, null, movements), scope);
        }

        audit.record(
                AUDIT_DISPATCHED,
                Subject.of("pick_list", list.pickListId()),
                Map.of("status", "OPEN"),
                Map.of("status", "DISPATCHED", "movements", movements.size()),
                scope);
        events.publish(new PickListDispatched(
                list.pickListId(), scope.entityId(), command.deliveryNoteId(), movements.size()));
        return movements.size();
    }
}
