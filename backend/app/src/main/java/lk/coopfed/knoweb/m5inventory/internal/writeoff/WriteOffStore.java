package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.query.EntityCost;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
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

    private final JdbcTemplate jdbc;
    private final InventoryQueries inventory;

    WriteOffStore(JdbcTemplate jdbc, InventoryQueries inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
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

    /** What the lot holds at the location; zero when there is none. */
    BigDecimal onHand(UUID locationId, UUID batchId, String condition) {
        List<BigDecimal> found = jdbc.queryForList(
                "select qty_on_hand from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                BigDecimal.class,
                locationId,
                batchId,
                condition);
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    /** The loss at the entity average (doc 25 DR-3: the value basis for the bands is the average, not retail). */
    BigDecimal value(List<Line> lines, ScopeContext scope) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            BigDecimal average = inventory
                    .entityAverageCost(line.skuId(), scope)
                    .map(EntityCost::avgCost)
                    .orElse(BigDecimal.ZERO);
            total = total.add(line.qty().multiply(average));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
