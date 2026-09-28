package lk.coopfed.knoweb.m3pricing.internal.ceiling;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of pricing.control_price, which every authenticated scope may read (23A section 3). Writes
 * are in the two handlers only. Also the CeilingResolver of 23A section 4: the ceiling in force for
 * a SKU in a unit on a date. Only SKU-scoped ceilings exist (tag scope is deferred), so "the lowest
 * of the SKU's and its tags' ceilings" (doc 23 DR-2) is the SKU's one row in force.
 */
@Component
public class ControlPriceStore {

    private static final String COLUMNS = "control_price_id, sku_id, ceiling_price, ceiling_uom_code, effective_from,"
            + " effective_to, gazette_reference, entered_by, entered_at";

    private final JdbcTemplate jdbc;

    ControlPriceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ControlPriceView> find(UUID controlPriceId) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from pricing.control_price where control_price_id = ?",
                        ControlPriceStore::row,
                        controlPriceId)
                .stream()
                .findFirst();
    }

    /** Every control price, newest first by SKU; of one SKU when {@code skuId} is not null. The history. */
    public List<ControlPriceView> list(UUID skuId) {
        StringBuilder sql =
                new StringBuilder("select " + COLUMNS + " from pricing.control_price where sku_id is not null");
        List<Object> args = new ArrayList<>();
        if (skuId != null) {
            sql.append(" and sku_id = ?");
            args.add(skuId);
        }
        sql.append(" order by sku_id, effective_from desc");
        return jdbc.query(sql.toString(), ControlPriceStore::row, args.toArray());
    }

    /** ControlPricesInForce(date) of doc 23 section 5.2. */
    public List<ControlPriceView> inForce(LocalDate date) {
        return jdbc.query(
                "select " + COLUMNS + " from pricing.control_price where sku_id is not null and effective_from <= ?"
                        + " and (effective_to is null or effective_to >= ?) order by sku_id",
                ControlPriceStore::row,
                Date.valueOf(date),
                Date.valueOf(date));
    }

    /**
     * ControlPriceFor(sku, date) in the line's unit: the lowest ceiling in force (there is at most
     * one per SKU, by the exclusion constraint). A ceiling in another unit does not match until
     * unit conversion lands (23A section 11; deferred).
     */
    public Optional<ControlPriceView> ceilingFor(UUID skuId, String uomCode, LocalDate date) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from pricing.control_price where sku_id = ? and ceiling_uom_code = ?"
                                + " and effective_from <= ? and (effective_to is null or effective_to >= ?)",
                        ControlPriceStore::row,
                        skuId,
                        uomCode,
                        Date.valueOf(date),
                        Date.valueOf(date))
                .stream()
                .min(Comparator.comparing(ControlPriceView::ceilingPrice));
    }

    /** The rows of the SKU whose range meets [from, to] (to null: open-ended). */
    List<ControlPriceView> overlapping(UUID skuId, LocalDate from, LocalDate to) {
        return jdbc.query(
                "select " + COLUMNS + " from pricing.control_price where sku_id = ?"
                        + " and daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]')"
                        + " && daterange(?::date, coalesce(?::date, 'infinity'::date), '[]') order by effective_from",
                ControlPriceStore::row,
                skuId,
                Date.valueOf(from),
                to == null ? null : Date.valueOf(to));
    }

    private static ControlPriceView row(ResultSet rs, int row) throws SQLException {
        Date to = rs.getDate("effective_to");
        return new ControlPriceView(
                rs.getObject("control_price_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getBigDecimal("ceiling_price"),
                rs.getString("ceiling_uom_code"),
                rs.getDate("effective_from").toLocalDate(),
                to == null ? null : to.toLocalDate(),
                rs.getString("gazette_reference"),
                rs.getObject("entered_by", UUID.class),
                rs.getTimestamp("entered_at").toInstant());
    }
}
