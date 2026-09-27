package lk.coopfed.knoweb.m8reporting.internal.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock position (28A section 3, {@code stock_position}; M8-03, the part the demo needs):
 * the quantity of each lot, by location, batch and condition, from M5's {@code stock.moved.v1}
 * (payload: movementId, ownerEntityId, locationId, stockLotId, batchId, skuId, condition,
 * movementType, qtyDelta, unitCostAtMovement, lotQtyOnHand, documentId, source, movementSeq).
 *
 * <p>The row takes the lot's quantity after the movement ({@code lotQtyOnHand}), not the sum of
 * the deltas: M5 sends the figure so that "a projection needs no read back", and a figure set
 * twice is the same figure, where a delta added twice is wrong. The movement's number keeps an
 * older event from overwriting a newer one: numbers are dense per location and source (25A), so
 * within a source the higher number wins, and between sources (a till's and the centre's) the
 * later event does.
 *
 * <p>The unit cost is the entity average at that movement (CR-28A-1). The stock value of a
 * report is quantity times that cost.
 */
@Component
public class StockPositionProjection extends Projection {

    public static final String NAME = "stock_position";
    static final String STOCK_MOVED = "stock.moved.v1";

    StockPositionProjection(JdbcTemplate jdbc, ProjectionStateStore state) {
        super(NAME, Set.of(STOCK_MOVED), jdbc, state);
    }

    @EventConsumer(types = "*", consumer = "m8." + NAME)
    @Transactional
    public void on(JsonNode envelope, ScopeContext scope) {
        consume(envelope, scope);
    }

    @Override
    protected void apply(ProjectionEvent event, ScopeContext scope) {
        jdbc.update(
                """
                insert into reporting.stock_position
                       (location_id, batch_id, condition, owner_entity_id, canonical_sku_id, qty_on_hand, unit_cost,
                        last_source, last_movement_seq, freshness)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (location_id, batch_id, condition) do update
                   set canonical_sku_id = excluded.canonical_sku_id,
                       qty_on_hand = excluded.qty_on_hand,
                       unit_cost = excluded.unit_cost,
                       last_source = excluded.last_source,
                       last_movement_seq = excluded.last_movement_seq,
                       freshness = excluded.freshness
                 where (reporting.stock_position.last_source = excluded.last_source
                        and reporting.stock_position.last_movement_seq < excluded.last_movement_seq)
                    or (reporting.stock_position.last_source <> excluded.last_source
                        and reporting.stock_position.freshness <= excluded.freshness)
                """,
                event.uuid("locationId"),
                event.uuid("batchId"),
                event.string("condition"),
                // The lot's owner as M5 reports it; row-level security refuses the row unless it
                // is the scope's entity, which the dispatcher took from the same event.
                event.uuid("ownerEntityId"),
                event.uuid("skuId"),
                event.decimal("lotQtyOnHand"),
                event.decimal("unitCostAtMovement"),
                event.string("source"),
                event.payload().path("movementSeq").asLong(),
                Timestamp.from(event.occurredAt()));
    }
}
