package lk.coopfed.knoweb.m5inventory.internal.repack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The repack handlers' reads, in their transaction and scope (row-level security applies). */
@Component
class RepackStore {

    record Recipe(
            UUID recipeId,
            UUID ownerEntityId,
            String name,
            UUID inputSkuId,
            BigDecimal inputQty,
            UUID outputSkuId,
            BigDecimal outputQty,
            BigDecimal expectedLossPct,
            String status) {}

    record Repack(
            UUID repackId,
            UUID ownerEntityId,
            UUID locationId,
            UUID inputBatchId,
            BigDecimal inputQty,
            BigDecimal inputUnitCost,
            UUID outputBatchId,
            BigDecimal actualOutputQty,
            BigDecimal outputUnitCost,
            boolean reversed) {}

    private final JdbcTemplate jdbc;
    private final StockOnHand stock;

    RepackStore(JdbcTemplate jdbc, StockOnHand stock) {
        this.jdbc = jdbc;
        this.stock = stock;
    }

    /** The recipe, locked for the rest of the transaction, or {@code m5.recipe.not_found}. */
    Recipe lockRecipe(UUID recipeId) {
        return jdbc
                .query(
                        """
                        select recipe_id, owner_entity_id, name, input_sku_id, input_qty, output_sku_id, output_qty,
                               expected_loss_pct, status
                          from inventory.repack_recipe
                         where recipe_id = ?
                           for update
                        """,
                        (rs, n) -> new Recipe(
                                rs.getObject("recipe_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getString("name"),
                                rs.getObject("input_sku_id", UUID.class),
                                rs.getBigDecimal("input_qty"),
                                rs.getObject("output_sku_id", UUID.class),
                                rs.getBigDecimal("output_qty"),
                                rs.getBigDecimal("expected_loss_pct"),
                                rs.getString("status")),
                        recipeId)
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new ProblemException("m5.recipe.not_found", Map.of("recipeId", String.valueOf(recipeId))));
    }

    boolean activeNameTaken(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.repack_recipe where lower(name) = lower(?) and status = 'ACTIVE')",
                Boolean.class,
                name));
    }

    /** The repack with whether it was reversed, or {@code m5.repack.not_found}. */
    Repack repack(UUID repackId) {
        return jdbc
                .query(
                        """
                        select r.repack_id, r.owner_entity_id, r.location_id, r.input_batch_id, r.input_qty,
                               r.input_unit_cost, r.output_batch_id, r.actual_output_qty, r.output_unit_cost,
                               v.repack_id is not null as reversed
                          from inventory.repack r
                          left join inventory.repack_reversal v on v.repack_id = r.repack_id
                         where r.repack_id = ?
                        """,
                        (rs, n) -> new Repack(
                                rs.getObject("repack_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("input_batch_id", UUID.class),
                                rs.getBigDecimal("input_qty"),
                                rs.getBigDecimal("input_unit_cost"),
                                rs.getObject("output_batch_id", UUID.class),
                                rs.getBigDecimal("actual_output_qty"),
                                rs.getBigDecimal("output_unit_cost"),
                                rs.getBoolean("reversed")),
                        repackId)
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new ProblemException("m5.repack.not_found", Map.of("repackId", String.valueOf(repackId))));
    }

    /**
     * What the GOOD lot of the batch holds at the location and is free, locked; zero when there is
     * none. What open pick lists hold of it for a delivery note is not free (wave 3, M1M2M3M5-20):
     * repacking or reversing it would leave the dispatch to take the lot below zero.
     */
    BigDecimal goodOnHand(UUID locationId, UUID batchId) {
        List<BigDecimal> found = jdbc.queryForList(
                """
                select qty_on_hand from inventory.stock_lot
                 where location_id = ? and batch_id = ? and condition = 'GOOD'
                   for update
                """,
                BigDecimal.class,
                locationId,
                batchId);
        if (found.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return found.get(0).subtract(stock.reserved(locationId, new StockOnHand.LotRef(batchId, "GOOD")));
    }

    /** The entity's quantity and average of one item (inventory.entity_sku_cost). */
    record ItemCost(BigDecimal qtyOnHand, BigDecimal avgCost) {}

    /** The entity's cost row of the item a batch's GOOD lot at the location is of; zeros when there is none. */
    ItemCost entityCostOfLot(UUID locationId, UUID batchId) {
        return jdbc
                .query(
                        """
                        select c.qty_on_hand, c.avg_cost
                          from inventory.stock_lot l
                          join inventory.entity_sku_cost c
                            on c.owner_entity_id = l.owner_entity_id and c.sku_id = l.sku_id
                         where l.location_id = ? and l.batch_id = ? and l.condition = 'GOOD'
                        """,
                        (rs, n) -> new ItemCost(rs.getBigDecimal("qty_on_hand"), rs.getBigDecimal("avg_cost")),
                        locationId,
                        batchId)
                .stream()
                .findFirst()
                .orElse(new ItemCost(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /** Whether anything but the repack itself moved the batch at the location: a sale, a transfer, a write-off. */
    boolean movedByOthers(UUID locationId, UUID batchId, UUID repackId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (select 1 from inventory.stock_movement
                                where location_id = ? and batch_id = ? and document_id <> ?)
                """,
                Boolean.class,
                locationId,
                batchId,
                repackId));
    }
}
