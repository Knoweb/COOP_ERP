package lk.coopfed.knoweb.m5inventory.internal.control;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.query.EntityCost;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The two reads a back-office decision on stock makes before it posts (wave 2; decided 6 October
 * 2026, {@code docs/progress/deviations/2026-10-06-wave2-stock-approvals.md} (1) and
 * {@code 2026-10-06-wave2-stock-movements.md} (4)), in the handler's transaction and scope:
 *
 * <ul>
 *   <li><b>the lots, locked</b> ({@code SELECT ... FOR UPDATE}) in the ledger's own order
 *       (location, batch, condition, as {@code LedgerService.LotKey} compares them), so the
 *       "does the lot hold it" guard and the posting see the same quantity and two decisions over
 *       several lines cannot deadlock (M5-12). The ledger then locks the same rows again, in the
 *       same order, which costs nothing;
 *   <li><b>the unit value</b> of a line: the entity average (doc 10 A-05, DR-3), else the lot's own
 *       acquisition cost when the entity has no average, else zero, which the caller routes to
 *       band 2 (M5-09).
 * </ul>
 */
@Component
public class StockOnHand {

    /** A lot of one location: its batch and condition. */
    public record LotRef(UUID batchId, String condition) {}

    private static final Comparator<LotRef> LEDGER_ORDER =
            Comparator.comparing(LotRef::batchId).thenComparing(LotRef::condition);

    private final JdbcTemplate jdbc;
    private final InventoryQueries inventory;

    StockOnHand(JdbcTemplate jdbc, InventoryQueries inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    /**
     * What each lot holds at the location, locked for the rest of the transaction; zero for a lot
     * that does not exist. The rows are locked in the ledger's order whatever the order given.
     */
    public Map<LotRef, BigDecimal> lockLots(UUID locationId, List<LotRef> lots) {
        TreeSet<LotRef> ordered = new TreeSet<>(LEDGER_ORDER);
        ordered.addAll(lots);
        Map<LotRef, BigDecimal> held = new LinkedHashMap<>();
        for (LotRef lot : ordered) {
            List<BigDecimal> found = jdbc.queryForList(
                    """
                    select qty_on_hand from inventory.stock_lot
                     where location_id = ? and batch_id = ? and condition = ?
                       for update
                    """,
                    BigDecimal.class,
                    locationId,
                    lot.batchId(),
                    lot.condition());
            held.put(lot, found.isEmpty() ? BigDecimal.ZERO : found.get(0));
        }
        return held;
    }

    /** The value of one unit of the lot: the entity average, else the lot's cost, else zero. */
    public BigDecimal unitValue(UUID skuId, UUID locationId, UUID batchId, String condition, ScopeContext scope) {
        BigDecimal average = inventory
                .entityAverageCost(skuId, scope)
                .map(EntityCost::avgCost)
                .orElse(BigDecimal.ZERO);
        if (average.signum() > 0) {
            return average;
        }
        return jdbc
                .queryForList(
                        "select unit_cost from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                        BigDecimal.class,
                        locationId,
                        batchId,
                        condition)
                .stream()
                .findFirst()
                .filter(cost -> cost.signum() > 0)
                .orElse(average);
    }
}
