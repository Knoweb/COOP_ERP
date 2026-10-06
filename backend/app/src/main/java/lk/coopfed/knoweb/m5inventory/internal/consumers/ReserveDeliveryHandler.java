package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.PickListCreated;
import lk.coopfed.knoweb.m5inventory.internal.consumers.ConsumerStore.Candidate;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pick list of an issued delivery note (25A section 6.2: "PickListService.create(dn): FEFO
 * suggestions per line; reservation = issued-undispatched qty for availability"; doc 25 section
 * 3.4). Applied by the system in the seller's scope.
 *
 * <p>Guards: the seller's OWN scope ({@code m5.delivery.seller_mismatch}); each line with an item
 * and a quantity above zero ({@code m5.delivery.line_invalid}). A delivery note that has its pick
 * list already gets the same one back.
 *
 * <p>Mutation: a pick list; per line, the seller's in-date GOOD lots of the item with free stock
 * and at least {@code inventory.dispatch_min_shelf_life_days} of life left (wave 2, M5-01), FEFO
 * across its locations (the delivery note does not say which warehouse it leaves from: decided on
 * the architect's delegation, recorded in the README), taken in order until the line is covered; a
 * row with no lot for what is short. Audit {@code PICK_LIST_CREATED}; event
 * {@code pick_list.created.v1}. Availability counts the picked quantities as reserved until
 * dispatch.
 *
 * <p>Permission {@code whs.pick}, the code of the pick list read of the slice (25A section 3.1:
 * "whs.pick (ENTITY)"); the system applies it with no user, so none is checked.
 */
@Service
@CommandHandler(permission = "whs.pick")
class ReserveDeliveryHandler implements Handles<ReserveDelivery, UUID> {

    static final String AUDIT_CREATED = "PICK_LIST_CREATED";

    private final ConsumerStore store;
    private final ControlPolicy policy;
    private final BusinessDay businessDay;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReserveDeliveryHandler(
            ConsumerStore store,
            ControlPolicy policy,
            BusinessDay businessDay,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.businessDay = businessDay;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReserveDelivery command, ScopeContext scope) {
        ConsumerGuards.requireScopeOf(command.sellerEntityId(), scope, "m5.delivery.seller_mismatch");
        for (ReserveDelivery.Line line : command.lines()) {
            if (line.skuId() == null || line.qty() == null || line.qty().signum() <= 0) {
                throw new ProblemException("m5.delivery.line_invalid", Map.of("lineId", String.valueOf(line.lineId())));
            }
        }
        Optional<ConsumerStore.PickList> existing = store.pickListOf(command.deliveryNoteId());
        if (existing.isPresent()) {
            return existing.get().pickListId();
        }

        UUID pickListId = Ids.next();
        jdbc.update(
                "insert into inventory.pick_list (pick_list_id, owner_entity_id, delivery_document_id) values (?, ?, ?)",
                pickListId,
                scope.entityId(),
                command.deliveryNoteId());

        // wave 2, M5-01: an expired lot never leaves on a delivery note, nor one with less shelf life
        // left than the seller asks of what it sends to another entity (0 days by default).
        LocalDate expiresFrom = businessDay.today().plusDays(policy.dispatchMinShelfLifeDays(scope));
        // What this list takes of each lot, so two lines of one item do not take the same units.
        Map<UUID, BigDecimal> takenHere = new HashMap<>();
        BigDecimal picked = BigDecimal.ZERO;
        BigDecimal shortQty = BigDecimal.ZERO;
        for (ReserveDelivery.Line line : command.lines()) {
            BigDecimal remaining = line.qty();
            List<Candidate> candidates = store.lockCandidates(line.skuId(), line.batchId(), expiresFrom);
            for (Candidate lot : candidates) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal free = lot.free().subtract(takenHere.getOrDefault(lot.stockLotId(), BigDecimal.ZERO));
                if (free.signum() <= 0) {
                    continue;
                }
                BigDecimal take = free.min(remaining);
                insertPick(pickListId, scope, line, lot.locationId(), lot.stockLotId(), lot.batchId(), take);
                takenHere.merge(lot.stockLotId(), take, BigDecimal::add);
                remaining = remaining.subtract(take);
                picked = picked.add(take);
            }
            if (remaining.signum() > 0) {
                insertPick(pickListId, scope, line, null, null, line.batchId(), remaining);
                shortQty = shortQty.add(remaining);
            }
        }

        audit.record(
                AUDIT_CREATED,
                Subject.of("pick_list", pickListId),
                null,
                Map.of("deliveryDocumentId", command.deliveryNoteId(), "picked", picked, "short", shortQty),
                scope);
        events.publish(new PickListCreated(pickListId, scope.entityId(), command.deliveryNoteId(), picked, shortQty));
        return pickListId;
    }

    private void insertPick(
            UUID pickListId,
            ScopeContext scope,
            ReserveDelivery.Line line,
            UUID locationId,
            UUID stockLotId,
            UUID batchId,
            BigDecimal qty) {
        jdbc.update(
                """
                insert into inventory.pick_list_line
                    (pick_line_id, pick_list_id, owner_entity_id, delivery_line_id, sku_id, location_id, stock_lot_id,
                     batch_id, qty)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.next(),
                pickListId,
                scope.entityId(),
                line.lineId(),
                line.skuId(),
                locationId,
                stockLotId,
                batchId,
                qty);
    }
}
