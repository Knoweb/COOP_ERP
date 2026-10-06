package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The write-off handlers' reads, in their transaction and scope (row-level security applies). */
@Component
class WriteOffStore {

    record Header(
            UUID writeOffId,
            UUID ownerEntityId,
            UUID locationId,
            LossCategory category,
            String status,
            UUID requestedBy,
            BigDecimal value,
            Integer band,
            UUID witnessUserId,
            boolean remoteWitness) {}

    record Line(UUID lineId, int lineNo, UUID batchId, UUID skuId, String condition, BigDecimal qty) {}

    /** The value of a write-off and whether a line of it has no cost at all (it routes to band 2). */
    record Valuation(BigDecimal value, boolean zeroCostLine) {}

    private final JdbcTemplate jdbc;
    private final StockOnHand stock;

    WriteOffStore(JdbcTemplate jdbc, StockOnHand stock) {
        this.jdbc = jdbc;
        this.stock = stock;
    }

    /** The write-off, locked for the rest of the transaction, or {@code m5.writeoff.not_found}. */
    Header lock(UUID writeOffId) {
        return jdbc
                .query(
                        """
                        select write_off_id, owner_entity_id, location_id, category, status, requested_by, value,
                               band, witness_user_id, remote_witness
                          from inventory.write_off
                         where write_off_id = ?
                           for update
                        """,
                        (rs, n) -> new Header(
                                rs.getObject("write_off_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                LossCategory.valueOf(rs.getString("category")),
                                rs.getString("status"),
                                rs.getObject("requested_by", UUID.class),
                                rs.getBigDecimal("value"),
                                (Integer) rs.getObject("band"),
                                rs.getObject("witness_user_id", UUID.class),
                                rs.getBoolean("remote_witness")),
                        writeOffId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException(
                        "m5.writeoff.not_found", Map.of("writeOffId", String.valueOf(writeOffId))));
    }

    List<Line> lines(UUID writeOffId) {
        return jdbc.query(
                """
                select line_id, line_no, batch_id, sku_id, condition, qty
                  from inventory.write_off_line
                 where write_off_id = ?
                 order by line_no
                """,
                (rs, n) -> new Line(
                        rs.getObject("line_id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("qty")),
                writeOffId);
    }

    List<UUID> photos(UUID writeOffId) {
        return jdbc.queryForList(
                "select attachment_id from inventory.write_off_photo where write_off_id = ? order by added_at, attachment_id",
                UUID.class,
                writeOffId);
    }

    /** What the lot holds at the location, unlocked (a draft's check; nothing posts); zero when there is none. */
    BigDecimal onHand(UUID locationId, UUID batchId, String condition) {
        List<BigDecimal> found = jdbc.queryForList(
                "select qty_on_hand from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                BigDecimal.class,
                locationId,
                batchId,
                condition);
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    /**
     * What each line's lot holds at the location, the lots locked in the ledger's order (wave 2,
     * M5-12): the guard and the posting see the same quantity, and two approvals over several
     * lines cannot deadlock.
     */
    Map<StockOnHand.LotRef, BigDecimal> lockLots(UUID locationId, List<Line> lines) {
        return stock.lockLots(
                locationId,
                lines.stream()
                        .map(line -> new StockOnHand.LotRef(line.batchId(), line.condition()))
                        .toList());
    }

    /**
     * The loss at the entity average (doc 25 DR-3: the value basis for the bands is the average, not
     * retail), a line of an item with no average at its lot's cost; a line with neither is of no
     * cost and routes the write-off to band 2 (wave 2, M5-09).
     */
    Valuation value(List<Line> lines, UUID locationId, ScopeContext scope) {
        BigDecimal total = BigDecimal.ZERO;
        boolean zeroCostLine = false;
        for (Line line : lines) {
            BigDecimal unit = stock.unitValue(line.skuId(), locationId, line.batchId(), line.condition(), scope);
            zeroCostLine |= unit.signum() == 0;
            total = total.add(line.qty().multiply(unit));
        }
        return new Valuation(total.setScale(2, RoundingMode.HALF_UP), zeroCostLine);
    }
}
