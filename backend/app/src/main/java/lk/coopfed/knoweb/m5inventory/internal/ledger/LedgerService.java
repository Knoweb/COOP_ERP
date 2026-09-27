package lk.coopfed.knoweb.m5inventory.internal.ledger;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.LotNegative;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.PostedMovement;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService.CostRow;
import lk.coopfed.knoweb.m5inventory.internal.ledger.LedgerStore.LotRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LedgerService.post (25A section 6.1): the only writer of {@code stock_lot.qty_on_hand},
 * {@code stock_movement} and {@code entity_sku_cost}. An internal command (CR-19A-6): M5's own
 * command handlers call it through {@link StockLedger}, in their transaction.
 *
 * <p>Guards, in order: an OWN scope with an entity; a document; at least one movement; each
 * movement complete, its quantity non-zero with at most three decimals, its sign the one its type
 * allows, its cost given when its type carries one; each location one the scope reads (M1), each
 * batch known (M2). A negative lot is not refused: an offline oversell takes it below zero and the
 * record shows what the till believed (doc 25 section 3.2).
 *
 * <p>Mutation, in an order that keeps two postings from deadlocking: every lot the command touches
 * is created if missing and locked, in the order of its identity; then the movement numbers are
 * drawn, a block per location, in location order (SequenceService of 25A: dense per location and
 * source, source 'central' or the device); then the entity's cost rows, in SKU order. The
 * movements are then applied in the order given: each is inserted with its number and its cost
 * ("costFor"), moves its lot, flips the lot's negative flag when it crosses zero, and moves the
 * entity average ({@link CostService}).
 *
 * <p>Audit: {@code STOCK_POSTED} for the document; {@code STOCK_LOT_NEGATIVE} (REVIEW) and
 * {@code STOCK_LOT_NEGATIVE_CLEARED} for a lot that crosses zero. Events: {@code stock.moved.v1}
 * per movement, {@code lot.negative.v1} per lot that goes below zero.
 */
@Service
@CommandHandler(permission = CommandHandler.INTERNAL)
public class LedgerService implements Handles<PostMovements, List<PostedMovement>>, StockLedger {

    static final String AUDIT_POSTED = "STOCK_POSTED";
    static final String AUDIT_NEGATIVE = "STOCK_LOT_NEGATIVE";
    static final String AUDIT_NEGATIVE_CLEARED = "STOCK_LOT_NEGATIVE_CLEARED";

    static final String CENTRAL = "central";

    private final LedgerStore store;
    private final JdbcTemplate jdbc;
    private final PartyQueries party;
    private final BatchQueries batches;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    LedgerService(
            LedgerStore store,
            JdbcTemplate jdbc,
            PartyQueries party,
            BatchQueries batches,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.store = store;
        this.jdbc = jdbc;
        this.party = party;
        this.batches = batches;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<PostedMovement> post(PostMovements command, ScopeContext scope) {
        return handle(command, scope);
    }

    @Override
    @Transactional
    public List<PostedMovement> handle(PostMovements command, ScopeContext scope) {
        // ---- guards ------------------------------------------------------------------------
        LedgerGuards.requireOwnScope(scope);
        LedgerGuards.requireCommand(command);
        UUID entity = scope.entityId();

        Map<UUID, UUID> skuOfBatch = new HashMap<>();
        for (Movement m : command.movements()) {
            if (skuOfBatch.containsKey(m.batchId())) {
                continue;
            }
            BatchView batch = batches.getBatch(m.batchId(), scope)
                    .orElseThrow(() -> new ProblemException("m5.batch.not_found", Map.of("batchId", m.batchId())));
            skuOfBatch.put(m.batchId(), batch.skuId());
        }
        command.movements().stream().map(Movement::locationId).distinct().forEach(location -> {
            if (party.getLocation(location, scope)
                    .filter(l -> entity.equals(l.ownerEntityId()))
                    .isEmpty()) {
                throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", location));
            }
        });

        // ---- mutation ----------------------------------------------------------------------
        Instant now = clock.instant();
        Instant occurredAt = command.occurredAt() == null ? now : command.occurredAt();
        String source = scope.deviceId() == null ? CENTRAL : scope.deviceId().toString();

        // 1. the lots, created where missing and locked, in the order of their identity
        Map<LotKey, LotRow> lots = new TreeMap<>();
        for (Movement m : command.movements()) {
            lots.putIfAbsent(LotKey.of(m), null);
        }
        Map<LotKey, Movement> firstMovementOf = new HashMap<>();
        for (Movement m : command.movements()) {
            firstMovementOf.putIfAbsent(LotKey.of(m), m);
        }
        for (LotKey key : lots.keySet()) {
            Movement first = firstMovementOf.get(key);
            UUID sku = skuOfBatch.get(key.batchId());
            BigDecimal startingCost = first.type().carriesItsOwnCost()
                    ? first.unitCost().setScale(CostService.COST_SCALE, CostService.ROUNDING)
                    : store.averageCost(entity, sku).orElse(BigDecimal.ZERO);
            jdbc.update(
                    """
                    insert into inventory.stock_lot
                        (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, condition, unit_cost, received_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (location_id, batch_id, condition) do nothing
                    """,
                    Ids.next(),
                    entity,
                    key.locationId(),
                    key.batchId(),
                    sku,
                    key.condition(),
                    startingCost,
                    Timestamp.from(now));
            LotRow lot = store.lockLot(key.locationId(), key.batchId(), key.condition())
                    .orElseThrow(() -> new IllegalStateException("The lot " + key + " was not created"));
            lots.put(key, lot);
        }

        // 2. the movement numbers: a block per location, in location order
        Map<UUID, Integer> countPerLocation = new TreeMap<>();
        for (Movement m : command.movements()) {
            countPerLocation.merge(m.locationId(), 1, Integer::sum);
        }
        Map<UUID, Long> nextSeq = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : countPerLocation.entrySet()) {
            Long last = jdbc.queryForObject(
                    """
                    insert into inventory.movement_sequence (location_id, source, owner_entity_id, last_seq)
                    values (?, ?, ?, ?)
                    on conflict (location_id, source)
                    do update set last_seq = inventory.movement_sequence.last_seq + excluded.last_seq
                    returning last_seq
                    """,
                    Long.class,
                    entry.getKey(),
                    source,
                    entity,
                    (long) entry.getValue());
            nextSeq.put(entry.getKey(), last - entry.getValue() + 1);
        }

        // 3. the entity's cost rows, in SKU order
        Map<UUID, CostRow> costs = new TreeMap<>();
        for (UUID sku : new TreeMap<>(invert(skuOfBatch)).keySet()) {
            jdbc.update(
                    """
                    insert into inventory.entity_sku_cost (owner_entity_id, sku_id, updated_at)
                    values (?, ?, ?)
                    on conflict (owner_entity_id, sku_id) do nothing
                    """,
                    entity,
                    sku,
                    Timestamp.from(now));
            costs.put(
                    sku,
                    store.lockCost(entity, sku)
                            .orElseThrow(
                                    () -> new IllegalStateException("The cost row of " + sku + " was not created")));
        }

        // 4. the movements, in the order given
        List<PostedMovement> posted = new ArrayList<>();
        List<StockMoved> moved = new ArrayList<>();
        Map<LotKey, Crossing> crossings = new LinkedHashMap<>();
        for (Movement m : command.movements()) {
            LotKey key = LotKey.of(m);
            LotRow lot = lots.get(key);
            UUID sku = lot.skuId();
            BigDecimal qty = m.qtyDelta().setScale(CostService.QTY_SCALE, CostService.ROUNDING);
            CostRow cost = costs.get(sku);
            BigDecimal costAtMovement = CostService.costAtMovement(cost, m.type(), m.unitCost());
            costs.put(sku, CostService.apply(cost, m.type(), qty, m.unitCost()));

            long seq = nextSeq.merge(m.locationId(), 1L, Long::sum) - 1;
            UUID movementId = Ids.next();
            jdbc.update(
                    """
                    insert into inventory.stock_movement
                        (movement_id, source, movement_seq, owner_entity_id, location_id, batch_id, sku_id, condition,
                         qty_delta, movement_type, unit_cost_at_movement, document_id, document_line_id,
                         occurred_at, occurred_local, operator_user_id, device_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    movementId,
                    source,
                    seq,
                    entity,
                    m.locationId(),
                    m.batchId(),
                    sku,
                    key.condition(),
                    qty,
                    m.type().name(),
                    costAtMovement,
                    command.documentId(),
                    m.documentLineId(),
                    Timestamp.from(occurredAt),
                    command.occurredLocal() == null ? null : Timestamp.valueOf(command.occurredLocal()),
                    scope.userId(),
                    scope.deviceId());

            BigDecimal after = lot.qtyOnHand().add(qty);
            Instant negativeSince = lot.negativeSince();
            if (after.signum() < 0 && negativeSince == null) {
                negativeSince = now;
                crossings.put(key, Crossing.WENT_NEGATIVE);
            } else if (after.signum() >= 0 && negativeSince != null) {
                negativeSince = null;
                crossings.merge(key, Crossing.CLEARED, (was, is) -> was == Crossing.WENT_NEGATIVE ? null : is);
            }
            LotRow updated = new LotRow(
                    lot.stockLotId(),
                    lot.ownerEntityId(),
                    lot.locationId(),
                    lot.batchId(),
                    sku,
                    lot.condition(),
                    after,
                    lot.unitCost(),
                    negativeSince,
                    seq);
            lots.put(key, updated);

            posted.add(new PostedMovement(movementId, lot.stockLotId(), source, seq, costAtMovement, after));
            moved.add(new StockMoved(
                    movementId,
                    entity,
                    m.locationId(),
                    lot.stockLotId(),
                    m.batchId(),
                    sku,
                    key.condition(),
                    m.type().name(),
                    qty,
                    costAtMovement,
                    after,
                    command.documentId(),
                    source,
                    seq));
        }

        for (LotRow lot : lots.values()) {
            jdbc.update(
                    """
                    update inventory.stock_lot
                       set qty_on_hand = ?, last_movement_seq = greatest(last_movement_seq, ?), negative_since = ?,
                           negative_acknowledged_at = case when ?::timestamptz is null then null
                                                           else negative_acknowledged_at end
                     where stock_lot_id = ?
                    """,
                    lot.qtyOnHand(),
                    lot.lastMovementSeq(),
                    timestamp(lot.negativeSince()),
                    timestamp(lot.negativeSince()),
                    lot.stockLotId());
        }
        for (Map.Entry<UUID, CostRow> entry : costs.entrySet()) {
            jdbc.update(
                    "update inventory.entity_sku_cost set qty_on_hand = ?, avg_cost = ?, updated_at = ?"
                            + " where owner_entity_id = ? and sku_id = ?",
                    entry.getValue().qtyOnHand(),
                    entry.getValue().avgCost(),
                    Timestamp.from(now),
                    entity,
                    entry.getKey());
        }

        // ---- audit -------------------------------------------------------------------------
        audit.record(
                AUDIT_POSTED,
                Subject.of("document", command.documentId()),
                null,
                Map.of("documentId", command.documentId(), "movements", posted),
                scope);
        crossings.forEach((key, crossing) -> {
            if (crossing == null) {
                return;
            }
            LotRow lot = lots.get(key);
            audit.record(
                    crossing == Crossing.WENT_NEGATIVE ? AUDIT_NEGATIVE : AUDIT_NEGATIVE_CLEARED,
                    Subject.of("stock_lot", lot.stockLotId()),
                    null,
                    Map.of("qtyOnHand", lot.qtyOnHand(), "documentId", command.documentId()),
                    scope);
        });

        // ---- events ------------------------------------------------------------------------
        moved.forEach(events::publish);
        crossings.forEach((key, crossing) -> {
            if (crossing == Crossing.WENT_NEGATIVE) {
                LotRow lot = lots.get(key);
                events.publish(new LotNegative(
                        lot.stockLotId(),
                        entity,
                        lot.locationId(),
                        lot.batchId(),
                        lot.skuId(),
                        lot.qtyOnHand(),
                        lot.negativeSince() == null ? now : lot.negativeSince(),
                        command.documentId()));
            }
        });

        return posted;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Map<UUID, UUID> invert(Map<UUID, UUID> skuOfBatch) {
        Map<UUID, UUID> bySku = new HashMap<>();
        skuOfBatch.forEach((batch, sku) -> bySku.put(sku, batch));
        return bySku;
    }

    /** Whether a lot crossed zero during the posting; null when it crossed and came back. */
    private enum Crossing {
        WENT_NEGATIVE,
        CLEARED
    }

    /** A lot's identity (location, batch, condition), ordered so every posting locks in one order. */
    record LotKey(UUID locationId, UUID batchId, String condition) implements Comparable<LotKey> {

        private static final Comparator<LotKey> ORDER = Comparator.comparing(LotKey::locationId)
                .thenComparing(LotKey::batchId)
                .thenComparing(LotKey::condition);

        static LotKey of(Movement m) {
            return new LotKey(m.locationId(), m.batchId(), m.condition().name());
        }

        @Override
        public int compareTo(LotKey other) {
            return ORDER.compare(this, other);
        }
    }
}
