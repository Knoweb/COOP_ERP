package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
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
 * <p>Since wave 2 (M5-02) the rows that were short at the reservation are picked again at dispatch,
 * FEFO from the in-date stock there is now; what is still not found is {@code
 * PICK_SHORT_DISPATCHED} (REVIEW) with the delivery line and quantity, never a negative posting.
 *
 * <p>Audit {@code PICK_LIST_DISPATCHED}; event {@code pick_list.dispatched.v1} (and the ledger's
 * {@code stock.moved.v1}). Permission {@code whs.pick}, as for the reservation.
 */
@Service
@CommandHandler(permission = "whs.pick")
class DispatchDeliveryHandler implements Handles<DispatchDelivery, Integer> {

    static final String AUDIT_DISPATCHED = "PICK_LIST_DISPATCHED";
    static final String AUDIT_SHORT_DISPATCHED = "PICK_SHORT_DISPATCHED";

    private final ConsumerStore store;
    private final ControlPolicy policy;
    private final BusinessDay businessDay;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    DispatchDeliveryHandler(
            ConsumerStore store,
            ControlPolicy policy,
            BusinessDay businessDay,
            StockLedger ledger,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.store = store;
        this.policy = policy;
        this.businessDay = businessDay;
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
        List<Movement> movements = new ArrayList<>(store.lotPicks(list.pickListId()).stream()
                .map(pick -> new Movement(
                        pick.locationId(),
                        pick.batchId(),
                        LotCondition.GOOD,
                        MovementType.TRANSFER_OUT,
                        pick.qty().negate(),
                        null,
                        null))
                .toList());
        // wave 2, M5-02: what was short at the reservation is picked again from the stock there is
        // now (a GRN may have landed since), FEFO and in date, before this list's reservation ends.
        // What is still not found is flagged, never posted negative: if the vehicle carried more
        // than the book held, the extra was unrecorded stock, which the buyer's GRN settles.
        LocalDate expiresFrom = businessDay.today().plusDays(policy.dispatchMinShelfLifeDays(scope));
        Map<UUID, BigDecimal> takenHere = new HashMap<>();
        List<Map<String, Object>> stillShort = new ArrayList<>();
        for (ConsumerStore.ShortPick shortPick : store.shortPicks(list.pickListId())) {
            BigDecimal remaining = shortPick.qty();
            for (ConsumerStore.Candidate lot :
                    store.lockCandidates(shortPick.skuId(), shortPick.batchId(), expiresFrom)) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal free = lot.free().subtract(takenHere.getOrDefault(lot.stockLotId(), BigDecimal.ZERO));
                if (free.signum() <= 0) {
                    continue;
                }
                BigDecimal take = free.min(remaining);
                movements.add(new Movement(
                        lot.locationId(),
                        lot.batchId(),
                        LotCondition.GOOD,
                        MovementType.TRANSFER_OUT,
                        take.negate(),
                        null,
                        null));
                takenHere.merge(lot.stockLotId(), take, BigDecimal::add);
                remaining = remaining.subtract(take);
            }
            if (remaining.signum() > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("deliveryLineId", String.valueOf(shortPick.deliveryLineId()));
                entry.put("skuId", shortPick.skuId());
                entry.put("qty", remaining.toPlainString());
                stillShort.add(entry);
            }
        }
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
        if (!stillShort.isEmpty()) {
            audit.record(
                    AUDIT_SHORT_DISPATCHED,
                    Subject.of("pick_list", list.pickListId()),
                    null,
                    Map.of("deliveryDocumentId", command.deliveryNoteId(), "lines", stillShort),
                    scope,
                    "Dispatched with lines the seller's stock could not cover; the buyer's GRN settles the difference");
        }
        events.publish(new PickListDispatched(
                list.pickListId(), scope.entityId(), command.deliveryNoteId(), movements.size()));
        return movements.size();
    }
}
